package app.attendancetracker.sync

import android.util.Log
import app.attendancetracker.data.AttendancePayload
import app.attendancetracker.data.AttendanceRecordEntity
import app.attendancetracker.data.AttendanceRecordItem
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.data.ExamHistoryEntity
import app.attendancetracker.data.SnapshotCategory
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.data.UrlSanitizer
import app.attendancetracker.network.FetchResult
import app.attendancetracker.network.PortalSyncClient
import app.attendancetracker.parser.AttendanceDateParser
import app.attendancetracker.parser.ParseResult
import app.attendancetracker.parser.PortalParsers
import app.attendancetracker.security.AuthSessionSnapshot
import app.attendancetracker.security.AuthSessionStateMachine
import app.attendancetracker.security.SessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToInt

data class SectionIdentifier(
    val courseCode: String,
    val category: SnapshotCategory,
)

data class SectionFailure(
    val courseCode: String,
    val category: SnapshotCategory,
    val reason: String,
)

data class SyncSummary(
    val semId: String?,
    val coursesDiscovered: Int,
    val succeededSections: List<SectionIdentifier>,
    val failedSections: List<SectionFailure>,
    val suspectSections: List<SectionIdentifier> = emptyList(),
    val syncedAt: Long,
)

sealed class SyncState {
    data object Idle : SyncState()
    data class Running(
        val currentCourse: Int,
        val totalCourses: Int,
        val courseCode: String? = null,
    ) : SyncState() {
        val progressText: String
            get() = if (totalCourses > 0) "course $currentCourse of $totalCourses" else "discovering courses"
    }
    data class Done(val summary: SyncSummary) : SyncState()
    data object NeedsLogin : SyncState()
    data class NeedsChallenge(val reason: String) : SyncState()
    data class Failed(val reason: String) : SyncState()
}

sealed class ExamHistorySyncResult {
    data class Synced(val semestersCount: Int) : ExamHistorySyncResult()
    data object SkippedRecentlySynced : ExamHistorySyncResult()
    data object NeedsLogin : ExamHistorySyncResult()
    data class NeedsChallenge(val reason: String) : ExamHistorySyncResult()
    data class Failed(val reason: String) : ExamHistorySyncResult()
}

/**
 * Coordinates foreground portal synchronization:
 * - Single-flight execution (only one sync runs at a time)
 * - 5-minute automatic throttle (bypassed when [forceRefresh] is true)
 * - Dynamic term & course discovery
 * - Sequential per-course ATTENDANCE, CIE, and LESSON_PLAN fetching with polite delay
 * - Separate on-demand / once-daily EXAM_HISTORY fetching
 * - Safe coroutine cancellation when the app closes
 */
class SyncManager(
    private val syncClient: PortalSyncClient,
    private val repository: AttendanceRepository,
    private val ensureSession: suspend () -> AuthSessionSnapshot,
    private val onSessionExpired: suspend () -> SessionState,
    private val requestDelayMs: Long = 100L,
    private val minSyncIntervalMs: Long = DEFAULT_MIN_SYNC_INTERVAL_MS,
    private val examHistoryMinIntervalMs: Long = DEFAULT_EXAM_HISTORY_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        syncClient: PortalSyncClient,
        repository: AttendanceRepository,
        stateMachine: AuthSessionStateMachine,
        requestDelayMs: Long = 100L,
        minSyncIntervalMs: Long = DEFAULT_MIN_SYNC_INTERVAL_MS,
        examHistoryMinIntervalMs: Long = DEFAULT_EXAM_HISTORY_INTERVAL_MS,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(
        syncClient = syncClient,
        repository = repository,
        ensureSession = {
            stateMachine.ensureSessionForSync()
            stateMachine.snapshot.value
        },
        onSessionExpired = { stateMachine.onSessionExpiredDuringSync() },
        requestDelayMs = requestDelayMs,
        minSyncIntervalMs = minSyncIntervalMs,
        examHistoryMinIntervalMs = examHistoryMinIntervalMs,
        clock = clock,
    )

    companion object {
        const val DEFAULT_MIN_SYNC_INTERVAL_MS = 5 * 60 * 1000L
        const val DEFAULT_EXAM_HISTORY_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    @Volatile
    private var activeSyncJob: Job? = null

    @Volatile
    private var lastSuccessfulSyncAtMs: Long? = null

    @Volatile
    private var lastExamHistorySyncAtMs: Long? = null

    /**
     * Runs a subject-wise sync for Attendance.
     * Sequential per-course to respect Joomla session affinity — the portal returns
     * the login page when too many parallel requests share the same cookie jar.
     * - Returns immediately if another sync is already in progress.
     * - Skips if synced within the last 5 minutes unless [forceRefresh] is true.
     */
    suspend fun sync(forceRefresh: Boolean = false): SyncState {
        if (!syncMutex.tryLock()) {
            return _state.value
        }

        activeSyncJob = currentCoroutineContext()[Job]
        try {
            val lastSync = lastSuccessfulSyncAtMs
            if (!forceRefresh && lastSync != null && (clock() - lastSync) < minSyncIntervalMs) {
                return _state.value
            }

            Log.d("SyncManager", "Starting sync(forceRefresh=$forceRefresh)")
            _state.value = SyncState.Running(currentCourse = 0, totalCourses = 0)

            val session = ensureSession()
            Log.d("SyncManager", "ensureSession returned state=${session.state}")
            when (session.state) {
                SessionState.FIRST_LOGIN -> {
                    _state.value = SyncState.NeedsLogin
                    return _state.value
                }
                SessionState.CHALLENGE_REQUIRED -> {
                    _state.value = SyncState.NeedsChallenge(
                        session.challengeReason ?: "Portal verification required.",
                    )
                    return _state.value
                }
                SessionState.SESSION_EXPIRED -> {
                    _state.value = SyncState.Failed(
                        session.errorMessage ?: "Session expired. Please retry.",
                    )
                    return _state.value
                }
                SessionState.SESSION_VALID -> Unit
            }

            var dashFetch = syncClient.fetchDashboard()
            Log.d("SyncManager", "fetchDashboard returned: ${dashFetch::class.simpleName}")

            if (dashFetch is FetchResult.SessionExpired) {
                Log.d("SyncManager", "Dashboard session expired, re-authenticating with onSessionExpired()")
                val handled = handleMidSyncSessionExpired(dashFetch.reason)
                if (handled !is SyncState.Running) {
                    return handled
                }
                dashFetch = syncClient.fetchDashboard()
            }

            when (dashFetch) {
                is FetchResult.SessionExpired -> {
                    Log.d("SyncManager", "Dashboard session still expired after re-auth: ${dashFetch.reason}")
                    return handleMidSyncSessionExpired(dashFetch.reason)
                }
                is FetchResult.Error -> {
                    Log.e("SyncManager", "Dashboard fetch error: ${dashFetch.reason}")
                    _state.value = SyncState.Failed(dashFetch.reason)
                    return _state.value
                }
                is FetchResult.Success -> {
                    Log.d("SyncManager", "Dashboard fetch success, finalUrl=${UrlSanitizer.sanitize(dashFetch.finalUrl)}, length=${dashFetch.data.length}")
                }
            }

            val parsedCourses = PortalParsers.parseCourseList(dashFetch.data)
            if (parsedCourses is ParseResult.Failure) {
                Log.e("SyncManager", "Course list parse failure: ${parsedCourses.reason}")
                _state.value = SyncState.Failed(parsedCourses.reason)
                return _state.value
            }

            val courseList = (parsedCourses as ParseResult.Success).data
            val termId = courseList.semId
            Log.d("SyncManager", "Discovered ${courseList.courses.size} courses for term=$termId")
            repository.recordTermAndCourses(
                semId = termId,
                semesterNumber = courseList.semesterNumber,
                label = courseList.termLabel,
                courses = courseList.courses.map { it.code to it.name },
                now = clock(),
            )

            val succeeded = mutableListOf<SectionIdentifier>()
            val failed = mutableListOf<SectionFailure>()
            val suspect = mutableListOf<SectionIdentifier>()
            val totalCourses = courseList.courses.size

            // Sequential fetch: ATTENDANCE ONLY for all courses.
            // Drastically faster, prevents connection exhaustion on parents.nie.ac.in,
            // and guarantees BIS516B (and every course) fetches successfully without socket drops.
            for ((index, course) in courseList.courses.withIndex()) {
                currentCoroutineContext().ensureActive()
                Log.d("SyncManager", "Fetching course ${index + 1}/$totalCourses: ${course.code}")

                _state.value = SyncState.Running(
                    currentCourse = index + 1,
                    totalCourses = totalCourses,
                    courseCode = course.code,
                )

                // ATTENDANCE
                val attUrl = course.attendanceUrl
                if (attUrl != null) {
                    Log.d("SyncManager", "  ATT ${course.code}: fetching ${UrlSanitizer.sanitize(attUrl)}")
                    var attFetch = syncClient.fetchHtml(attUrl)
                    if (attFetch is FetchResult.SessionExpired) {
                        Log.w("SyncManager", "  ATT ${course.code}: session expired mid-sync, re-authenticating")
                        val handled = handleMidSyncSessionExpired(attFetch.reason)
                        if (handled !is SyncState.Running) {
                            return handled
                        }
                        attFetch = syncClient.fetchHtml(attUrl)
                    }

                    when (attFetch) {
                        is FetchResult.SessionExpired -> {
                            Log.w("SyncManager", "  ATT ${course.code}: session expired mid-sync")
                            return handleMidSyncSessionExpired(attFetch.reason)
                        }
                        is FetchResult.Error -> {
                            Log.e("SyncManager", "  ATT ${course.code}: fetch error ${attFetch.reason}")
                            recordFailedSection(
                                course.code, SnapshotCategory.ATTENDANCE, termId,
                                attFetch.reason, attUrl, failed,
                            )
                        }
                        is FetchResult.Success -> {
                            when (val parsed = PortalParsers.parseAttendance(attFetch.data, course.code)) {
                                is ParseResult.Failure -> {
                                    Log.e("SyncManager", "  ATT ${course.code}: parse failure ${parsed.reason}")
                                    recordFailedSection(
                                        course.code, SnapshotCategory.ATTENDANCE, termId,
                                        parsed.reason, attUrl, failed,
                                    )
                                }
                                is ParseResult.Success -> {
                                    val d = parsed.data
                                    Log.d("SyncManager", "  ATT ${course.code}: present=${d.presentCount} conducted=${d.conductedCount}")

                                    // Parse and persist date-wise Present and Absent history records
                                    val presentEntities = d.presentRows.map { row ->
                                        val parsedDate = AttendanceDateParser.parse(row.date, row.time)
                                        val slot = AttendanceDateParser.formatSlot(row.time)
                                        val key = "${row.date.trim()}|${row.time.trim()}|PRESENT|${row.slNo.trim()}"
                                        AttendanceRecordEntity(
                                            courseCode = course.code,
                                            term = termId,
                                            sessionKey = key,
                                            date = parsedDate.formattedDate,
                                            slot = slot,
                                            status = "PRESENT",
                                            dateMillis = parsedDate.epochMillis,
                                            rawDateText = row.date.trim(),
                                            fetchedAt = clock(),
                                        )
                                    }

                                    val absentEntities = d.absentRows.map { row ->
                                        val parsedDate = AttendanceDateParser.parse(row.date, row.time)
                                        val slot = AttendanceDateParser.formatSlot(row.time)
                                        val key = "${row.date.trim()}|${row.time.trim()}|ABSENT|${row.slNo.trim()}"
                                        AttendanceRecordEntity(
                                            courseCode = course.code,
                                            term = termId,
                                            sessionKey = key,
                                            date = parsedDate.formattedDate,
                                            slot = slot,
                                            status = "ABSENT",
                                            dateMillis = parsedDate.epochMillis,
                                            rawDateText = row.date.trim(),
                                            fetchedAt = clock(),
                                        )
                                    }

                                    val allEntities = presentEntities + absentEntities
                                    repository.recordAttendanceRecords(allEntities)

                                    val recordItems = allEntities.map {
                                        AttendanceRecordItem(
                                            date = it.date,
                                            slot = it.slot,
                                            status = it.status,
                                            dateMillis = it.dateMillis,
                                            rawDateText = it.rawDateText,
                                        )
                                    }

                                    val payload = json.encodeToString(
                                        AttendancePayload(
                                            attended = d.presentCount,
                                            conducted = d.conductedCount,
                                            stillToGo = d.stillToGoCount,
                                            unit = "classes",
                                            records = recordItems,
                                        ),
                                    )
                                    repository.recordSnapshot(
                                        courseCode = course.code,
                                        category = SnapshotCategory.ATTENDANCE,
                                        term = termId,
                                        status = SnapshotStatus.OK,
                                        payloadJson = payload,
                                        errorMessage = null,
                                        sourceUrl = attUrl,
                                        fetchedAt = clock(),
                                    )
                                    succeeded += SectionIdentifier(course.code, SnapshotCategory.ATTENDANCE)
                                }
                            }
                        }
                    }
                }

                // Small 100ms pause between courses to avoid flooding
                if (index < courseList.courses.size - 1 && requestDelayMs > 0) {
                    delay(requestDelayMs)
                }
            }

            val completedAt = clock()
            lastSuccessfulSyncAtMs = completedAt
            val summary = SyncSummary(
                semId = termId,
                coursesDiscovered = totalCourses,
                succeededSections = succeeded,
                failedSections = failed,
                suspectSections = suspect,
                syncedAt = completedAt,
            )
            _state.value = SyncState.Done(summary)
            return _state.value
        } catch (ce: CancellationException) {
            if (_state.value is SyncState.Running) {
                _state.value = SyncState.Idle
            }
            throw ce
        } finally {
            activeSyncJob = null
            syncMutex.unlock()
        }
    }

    /**
     * Fetches Exam History separately when the student opens the Results screen,
     * throttled to at most once per day unless [force] is true.
     * Never writes into attendance or CIE snapshots.
     */
    suspend fun syncExamHistory(usn: String, force: Boolean = false): ExamHistorySyncResult {
        val lastExam = lastExamHistorySyncAtMs
        if (!force && lastExam != null && (clock() - lastExam) < examHistoryMinIntervalMs) {
            return ExamHistorySyncResult.SkippedRecentlySynced
        }

        val session = ensureSession()
        when (session.state) {
            SessionState.FIRST_LOGIN -> return ExamHistorySyncResult.NeedsLogin
            SessionState.CHALLENGE_REQUIRED -> return ExamHistorySyncResult.NeedsChallenge(
                session.challengeReason ?: "Portal verification required.",
            )
            SessionState.SESSION_EXPIRED -> return ExamHistorySyncResult.Failed(
                session.errorMessage ?: "Session expired.",
            )
            SessionState.SESSION_VALID -> Unit
        }

        return when (val fetch = syncClient.fetchExamHistory(usn)) {
            is FetchResult.SessionExpired -> {
                val next = onSessionExpired()
                if (next == SessionState.FIRST_LOGIN) {
                    ExamHistorySyncResult.NeedsLogin
                } else {
                    ExamHistorySyncResult.Failed(fetch.reason)
                }
            }
            is FetchResult.Error -> ExamHistorySyncResult.Failed(fetch.reason)
            is FetchResult.Success -> {
                when (val parsed = PortalParsers.parseExamHistory(fetch.data)) {
                    is ParseResult.Failure -> ExamHistorySyncResult.Failed(parsed.reason)
                    is ParseResult.Success -> {
                        val now = clock()
                        repository.recordExamHistory(
                            parsed.data.semesters.map { sem ->
                                ExamHistoryEntity(
                                    headerLabel = sem.headerLabel,
                                    semesterNumber = sem.semesterNumber,
                                    creditsRegistered = sem.creditsRegistered,
                                    creditsEarned = sem.creditsEarned,
                                    sgpa = sem.sgpa,
                                    coursesJson = json.encodeToString(sem.courses),
                                    fetchedAt = now,
                                    sourceUrl = fetch.finalUrl,
                                )
                            },
                        )
                        lastExamHistorySyncAtMs = now
                        ExamHistorySyncResult.Synced(parsed.data.semesters.size)
                    }
                }
            }
        }
    }

    /**
     * Safely cancels an in-flight sync when the app closes or leaves foreground.
     */
    fun cancel() {
        activeSyncJob?.cancel()
    }

    private suspend fun handleMidSyncSessionExpired(reason: String): SyncState {
        val next = onSessionExpired()
        return when (next) {
            SessionState.FIRST_LOGIN -> {
                _state.value = SyncState.NeedsLogin
                _state.value
            }
            SessionState.CHALLENGE_REQUIRED -> {
                _state.value = SyncState.NeedsChallenge(reason)
                _state.value
            }
            SessionState.SESSION_EXPIRED -> {
                _state.value = SyncState.Failed("Session expired during sync: $reason")
                _state.value
            }
            SessionState.SESSION_VALID -> {
                _state.value
            }
        }
    }

    private suspend fun recordFailedSection(
        courseCode: String,
        category: SnapshotCategory,
        termId: String,
        reason: String,
        sourceUrl: String,
        failed: MutableList<SectionFailure>,
    ) {
        repository.recordSnapshot(
            courseCode = courseCode,
            category = category,
            term = termId,
            status = SnapshotStatus.FAILED,
            payloadJson = null,
            errorMessage = reason,
            sourceUrl = sourceUrl,
            fetchedAt = clock(),
        )
        failed += SectionFailure(courseCode, category, reason)
    }
}
