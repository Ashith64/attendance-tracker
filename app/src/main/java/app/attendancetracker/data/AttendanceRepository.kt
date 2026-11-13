package app.attendancetracker.data

import app.attendancetracker.engine.AttendanceEngine
import app.attendancetracker.engine.AttendanceInput
import app.attendancetracker.engine.AttendanceResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Per-course UI model combining the latest OK attendance snapshot, [AttendanceEngine] calculation,
 * last-synced timestamp, and staleness when the newest fetch attempt failed.
 * Missing data is represented as null (never 0).
 */
data class CourseAttendanceUiModel(
    val courseCode: String,
    val courseName: String,
    val term: String,
    val attended: Int?,
    val conducted: Int?,
    val engineResult: AttendanceResult?,
    val lastSyncedAt: Long?,
    val isStale: Boolean,
    val isSuspect: Boolean = false,
    val latestErrorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceRepository(
    private val dao: PortalDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    fun observeAllTerms(): Flow<List<TermEntity>> = dao.allTerms()

    fun observeLatestTerm(): Flow<TermEntity?> = dao.latestTerm()

    fun observeAllCourses(): Flow<List<CourseEntity>> = dao.allCourses()

    fun observeHistory(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<List<SnapshotEntity>> = dao.history(courseCode, category, term)

    fun observeLatestOkSnapshot(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): Flow<SnapshotEntity?> = dao.latestOkSnapshot(courseCode, category, term)

    fun observeCoursesInLatestTerm(): Flow<List<CourseEntity>> =
        dao.latestTerm().flatMapLatest { term ->
            if (term == null) flowOf(emptyList()) else dao.coursesForTerm(term.semId)
        }

    fun observeSnapshotsForLatestTerm(): Flow<List<SnapshotEntity>> =
        dao.latestTerm().flatMapLatest { term ->
            if (term == null) flowOf(emptyList()) else dao.snapshotsForTerm(term.semId)
        }

    fun observeLayoutChangedWarningForLatestTerm(): Flow<Boolean> =
        observeSnapshotsForLatestTerm().map { snapshots ->
            snapshots
                .groupBy { it.courseCode to it.category }
                .values
                .any { attempts ->
                    attempts.size >= 2 &&
                        attempts[0].status == SnapshotStatus.FAILED &&
                        attempts[1].status == SnapshotStatus.FAILED
                }
        }

    fun observeCoursesForLatestTerm(thresholdPercent: Int = 75): Flow<List<CourseAttendanceUiModel>> =
        dao.latestTerm().flatMapLatest { term ->
            if (term == null) {
                flowOf(emptyList())
            } else {
                observeCoursesForTerm(term.semId, thresholdPercent)
            }
        }

    fun observeCoursesForTerm(
        term: String,
        thresholdPercent: Int = 75,
    ): Flow<List<CourseAttendanceUiModel>> =
        dao.coursesForTerm(term).flatMapLatest { courses ->
            if (courses.isEmpty()) {
                flowOf(emptyList())
            } else {
                val perCourseFlows = courses.map { course ->
                    combine(
                        dao.latestOkSnapshot(course.code, SnapshotCategory.ATTENDANCE, course.term),
                        dao.latestAttempt(course.code, SnapshotCategory.ATTENDANCE, course.term),
                    ) { latestOk, latestAttempt ->
                        buildCourseUiModel(
                            course = course,
                            latestOk = latestOk,
                            latestAttempt = latestAttempt,
                            thresholdPercent = thresholdPercent,
                        )
                    }
                }
                combine(perCourseFlows) { items -> items.toList() }
            }
        }

    private fun buildCourseUiModel(
        course: CourseEntity,
        latestOk: SnapshotEntity?,
        latestAttempt: SnapshotEntity?,
        thresholdPercent: Int,
    ): CourseAttendanceUiModel {
        val isStale = latestAttempt?.status == SnapshotStatus.FAILED
        val isSuspect = latestAttempt?.status == SnapshotStatus.SUSPECT
        val errorMsg = if (isStale || isSuspect) latestAttempt?.errorMessage else null

        val payload = latestOk?.payloadJson?.let { raw ->
            runCatching { json.decodeFromString(AttendancePayload.serializer(), raw) }.getOrNull()
        }

        if (payload == null) {
            return CourseAttendanceUiModel(
                courseCode = course.code,
                courseName = course.name,
                term = course.term,
                attended = null,
                conducted = null,
                engineResult = null,
                lastSyncedAt = null,
                isStale = isStale,
                isSuspect = isSuspect,
                latestErrorMessage = errorMsg,
            )
        }

        val engineResult = AttendanceEngine.calculate(
            AttendanceInput(
                attended = payload.attended,
                conducted = payload.conducted,
                thresholdPercent = thresholdPercent,
            ),
        )

        return CourseAttendanceUiModel(
            courseCode = course.code,
            courseName = course.name,
            term = course.term,
            attended = payload.attended,
            conducted = payload.conducted,
            engineResult = engineResult,
            lastSyncedAt = latestOk.fetchedAt,
            isStale = isStale,
            isSuspect = isSuspect,
            latestErrorMessage = errorMsg,
        )
    }

    suspend fun recordTermAndCourses(
        semId: String,
        semesterNumber: Int,
        label: String,
        courses: List<Pair<String, String>>,
        now: Long,
    ) {
        dao.upsertDiscoveredTerm(
            semId = semId,
            semesterNumber = semesterNumber,
            label = label,
            now = now,
        )
        if (courses.isNotEmpty()) {
            dao.upsertCourses(
                courses.map { (code, name) ->
                    CourseEntity(
                        code = code,
                        name = name,
                        term = semId,
                        lastSeenAt = now,
                    )
                },
            )
        }
    }

    suspend fun recordSnapshot(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
        status: SnapshotStatus,
        payloadJson: String?,
        errorMessage: String?,
        sourceUrl: String,
        fetchedAt: Long,
        keepCount: Int = 200,
    ): Long {
        val sanitizedUrl = UrlSanitizer.sanitize(sourceUrl)
        val id = dao.insertSnapshot(
            SnapshotEntity(
                courseCode = courseCode,
                category = category,
                payloadJson = if (status == SnapshotStatus.OK) payloadJson else null,
                term = term,
                fetchedAt = fetchedAt,
                status = status,
                errorMessage = errorMessage,
                sourceUrl = sanitizedUrl,
            ),
        )
        dao.pruneSnapshots(
            courseCode = courseCode,
            category = category,
            term = term,
            keepCount = keepCount,
        )
        return id
    }

    suspend fun latestAttemptStatus(
        courseCode: String,
        category: SnapshotCategory,
        term: String,
    ): SnapshotStatus? = dao.latestAttemptStatus(courseCode, category, term)

    fun observeExamHistory(): Flow<List<ExamHistoryEntity>> = dao.allExamHistory()

    suspend fun recordExamHistory(records: List<ExamHistoryEntity>) {
        if (records.isNotEmpty()) {
            dao.upsertExamHistory(
                records.map { it.copy(sourceUrl = UrlSanitizer.sanitize(it.sourceUrl)) },
            )
        }
    }

    fun observeAttendanceRecords(courseCode: String, term: String): Flow<List<AttendanceRecordEntity>> =
        dao.observeAttendanceRecords(courseCode, term)

    suspend fun recordAttendanceRecords(records: List<AttendanceRecordEntity>) {
        if (records.isNotEmpty()) {
            dao.upsertAttendanceRecords(records)
        }
    }
}
