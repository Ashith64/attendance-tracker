package app.attendancetracker.sync

import app.attendancetracker.data.AttendanceRecordEntity
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.data.CourseEntity
import app.attendancetracker.data.ExamHistoryEntity
import app.attendancetracker.data.PortalDao
import app.attendancetracker.data.SnapshotCategory
import app.attendancetracker.data.SnapshotEntity
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.data.TermEntity
import app.attendancetracker.network.FetchResult
import app.attendancetracker.network.PortalSyncClient
import app.attendancetracker.security.AuthSessionSnapshot
import app.attendancetracker.security.SessionState
import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class InMemorySyncDao : PortalDao {
    private val termsFlow = MutableStateFlow<List<TermEntity>>(emptyList())
    private val coursesFlow = MutableStateFlow<List<CourseEntity>>(emptyList())
    private val snapshotsFlow = MutableStateFlow<List<SnapshotEntity>>(emptyList())
    private val examHistoryFlow = MutableStateFlow<List<ExamHistoryEntity>>(emptyList())
    private var nextId = 1L

    override fun allTerms(): Flow<List<TermEntity>> = termsFlow
    override fun latestTerm(): Flow<TermEntity?> = termsFlow.map { it.firstOrNull { t -> t.isLatest } }
    override suspend fun getTermById(semId: String): TermEntity? = termsFlow.value.firstOrNull { it.semId == semId }
    override suspend fun upsertTerm(term: TermEntity) {
        termsFlow.value = termsFlow.value.filterNot { it.semId == term.semId } + term
    }

    override suspend fun clearLatestExcept(latestSemId: String) {
        termsFlow.value = termsFlow.value.map { if (it.semId == latestSemId) it else it.copy(isLatest = false) }
    }

    override fun allCourses(): Flow<List<CourseEntity>> = coursesFlow
    override fun coursesForTerm(term: String): Flow<List<CourseEntity>> =
        coursesFlow.map { it.filter { c -> c.term == term } }

    override suspend fun upsertCourses(courses: List<CourseEntity>) {
        val keys = courses.map { it.code to it.term }.toSet()
        coursesFlow.value = coursesFlow.value.filterNot { (it.code to it.term) in keys } + courses
    }

    override fun latestOkSnapshot(courseCode: String, category: SnapshotCategory, term: String): Flow<SnapshotEntity?> =
        snapshotsFlow.map { list ->
            list.filter { it.courseCode == courseCode && it.category == category && it.term == term && it.status == SnapshotStatus.OK }
                .maxWithOrNull(compareBy<SnapshotEntity> { it.fetchedAt }.thenBy { it.id })
        }

    override suspend fun latestAttemptStatus(courseCode: String, category: SnapshotCategory, term: String): SnapshotStatus? =
        snapshotsFlow.value.filter { it.courseCode == courseCode && it.category == category && it.term == term }
            .maxWithOrNull(compareBy<SnapshotEntity> { it.fetchedAt }.thenBy { it.id })?.status

    override fun latestAttempt(courseCode: String, category: SnapshotCategory, term: String): Flow<SnapshotEntity?> =
        snapshotsFlow.map { list ->
            list.filter { it.courseCode == courseCode && it.category == category && it.term == term }
                .maxWithOrNull(compareBy<SnapshotEntity> { it.fetchedAt }.thenBy { it.id })
        }

    override fun history(courseCode: String, category: SnapshotCategory, term: String): Flow<List<SnapshotEntity>> =
        snapshotsFlow.map { list -> list.filter { it.courseCode == courseCode && it.category == category && it.term == term } }

    override fun snapshotsForTerm(term: String): Flow<List<SnapshotEntity>> =
        snapshotsFlow.map { list ->
            list.filter { it.term == term }
                .sortedWith(compareByDescending<SnapshotEntity> { it.fetchedAt }.thenByDescending { it.id })
        }

    override suspend fun insertSnapshot(snapshot: SnapshotEntity): Long {
        val s = snapshot.copy(id = nextId++)
        snapshotsFlow.value = snapshotsFlow.value + s
        return s.id
    }

    override suspend fun pruneSnapshots(courseCode: String, category: SnapshotCategory, term: String, keepCount: Int) = Unit
    override fun allExamHistory(): Flow<List<ExamHistoryEntity>> = examHistoryFlow
    override suspend fun upsertExamHistory(records: List<ExamHistoryEntity>) {
        examHistoryFlow.value = records
    }
    override fun observeAttendanceRecords(courseCode: String, term: String): Flow<List<AttendanceRecordEntity>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
    override suspend fun upsertAttendanceRecords(records: List<AttendanceRecordEntity>) = Unit
    override suspend fun clearAttendanceRecordsForCourse(courseCode: String, term: String) = Unit
}

private class FakePortalSyncClient(
    private val dashboardHandler: suspend () -> FetchResult<String>,
    private val htmlHandler: suspend (String) -> FetchResult<String>,
    private val examHistoryHandler: suspend (String) -> FetchResult<String> = { FetchResult.Error("Unused") },
) : PortalSyncClient {
    val dashboardCalls = AtomicInteger(0)
    val htmlCalls = mutableListOf<String>()
    val examHistoryCalls = AtomicInteger(0)

    override suspend fun fetchDashboard(): FetchResult<String> {
        dashboardCalls.incrementAndGet()
        return dashboardHandler()
    }

    override suspend fun fetchHtml(portalUrl: String): FetchResult<String> {
        htmlCalls += portalUrl
        return htmlHandler(portalUrl)
    }

    override suspend fun fetchExamHistory(usn: String): FetchResult<String> {
        examHistoryCalls.incrementAndGet()
        return examHistoryHandler(usn)
    }
}

class SyncManagerTest {

    private fun dashboardHtml(
        semId: String,
        semLabel: String,
        courses: List<Pair<String, String>>,
    ): String {
        val rows = courses.mapIndexed { idx, (code, name) ->
            val id = idx + 1
            """
            <tr>
              <td>$code</td><td>$name</td>
              <td><a href="index.php?task=getuploadlession&courseId=$id&secId=&semId=$semId">Lesson</a></td>
              <td><a href="index.php?task=attendencelist&courseId=$id&secId=&semId=$semId">Attendance</a></td>
              <td><a href="index.php?task=ciedetails&courseId=$id&secId=&semId=$semId">CIE</a></td>
            </tr>
            """.trimIndent()
        }.joinToString("\n")
        return """
            <html><body>
              <div class="cn-stu-data1"><p>$semLabel</p></div>
              <table class="cn-pay-table"><tbody>$rows</tbody></table>
            </body></html>
        """.trimIndent()
    }

    private fun attendanceHtml(code: String, present: Int, absent: Int): String {
        val pRows = (1..present).joinToString("") { "<tr><td>$it</td><td>01-01-2026</td><td>09:00 TO 10:00</td><td>Present</td></tr>" }
        val aRows = (1..absent).joinToString("") { "<tr><td>$it</td><td>02-01-2026</td><td>09:00 TO 10:00</td><td>Absent</td></tr>" }
        return """
            <html><body>
              <h3 class="md-card-head-text"><span>$code - Subject</span></h3>
              <div class="cn-legend">
                <span class="cn-attend">Present[$present]</span>
                <span class="cn-absent">Absent [$absent]</span>
                <span class="cn-still">Still to go [0]</span>
              </div>
              <table class="cn-attend-list1"><tbody>$pRows</tbody></table>
              <table class="cn-attend-list2"><tbody>$aRows</tbody></table>
            </body></html>
        """.trimIndent()
    }

    private fun cieHtml(code: String, attPct: Int): String = """
        <html><body>
          <h3 class="md-card-head-text"><span>$code - Subject</span></h3>
          <table class="cn-cie-table"><caption>Subject( $code)</caption><tbody><tr>
            <td>T1 : 18</td><td>Current CIE : 18</td><td>ATTENDANCE : $attPct%</td>
          </tr></tbody></table>
          <script>var chartData = [{"xaxis":"T1","col1":18,"col2":2,"maxmarks":20,"optainmarks":15,"linevalue":15,}];</script>
        </body></html>
    """.trimIndent()

    private fun lessonHtml(code: String): String = """
        <html><body>
          <form id="lessionUpload"><div class="cn-basic-details">
            <table class="cn-stu-info"><tbody><tr><td>Course Code: $code</td></tr></tbody></table>
          </div></form>
        </body></html>
    """.trimIndent()

    private fun examHistoryHtml(): String = """
        <html><body><div class="result-table">
          <div class="cn-blocks"><h6>Even 2026 - Semester : 6 <span class="cn-bgcolor1">SGPA: 9.0</span></h6></div>
          <table class="res-table"><tbody><tr><td>C101</td><td>Course One</td><td>4</td><td>4</td><td>9</td><td>A+</td></tr></tbody></table>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `all succeed emits progress states and persists snapshots without fetching exam history`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        val client = FakePortalSyncClient(
            dashboardHandler = {
                FetchResult.Success(
                    dashboardHtml("77", "B.E, SEM 06, SEC A", listOf("C101" to "Course One", "C102" to "Course Two")),
                    "https://parents.nie.ac.in/dashboard",
                )
            },
            htmlHandler = { url ->
                val code = if (url.contains("courseId=1")) "C101" else "C102"
                when {
                    url.contains("attendencelist") -> FetchResult.Success(attendanceHtml(code, 4, 1), url)
                    url.contains("ciedetails") -> FetchResult.Success(cieHtml(code, 80), url)
                    url.contains("getuploadlession") -> FetchResult.Success(lessonHtml(code), url)
                    else -> FetchResult.Error("Unexpected URL")
                }
            },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
            clock = { 1_000L },
        )

        manager.state.test {
            assertEquals(SyncState.Idle, awaitItem())
            val finalState = manager.sync()
            assertEquals(SyncState.Running(0, 0), awaitItem())
            assertEquals(SyncState.Running(1, 2, "C101"), awaitItem())
            assertEquals(SyncState.Running(2, 2, "C102"), awaitItem())
            val done = awaitItem() as SyncState.Done
            assertEquals(finalState, done)
            assertEquals("77", done.summary.semId)
            assertEquals(2, done.summary.coursesDiscovered)
            assertEquals(2, done.summary.succeededSections.size)
            assertTrue(done.summary.failedSections.isEmpty())
            assertEquals(0, client.examHistoryCalls.get())
        }

        val courses = repo.observeCoursesForLatestTerm().first()
        assertEquals(2, courses.size)
        assertEquals(4, courses.first { it.courseCode == "C101" }.attended)
    }

    @Test
    fun `one course failure does not abort other courses`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        val client = FakePortalSyncClient(
            dashboardHandler = {
                FetchResult.Success(
                    dashboardHtml("77", "B.E, SEM 06, SEC A", listOf("C101" to "Course One", "C102" to "Course Two")),
                    "https://parents.nie.ac.in/dashboard",
                )
            },
            htmlHandler = { url ->
                if (url.contains("courseId=1")) {
                    FetchResult.Error("HTTP 500")
                } else {
                    when {
                        url.contains("attendencelist") -> FetchResult.Success(attendanceHtml("C102", 8, 2), url)
                        url.contains("ciedetails") -> FetchResult.Success(cieHtml("C102", 80), url)
                        url.contains("getuploadlession") -> FetchResult.Success(lessonHtml("C102"), url)
                        else -> FetchResult.Error("Unexpected URL")
                    }
                }
            },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
            clock = { 1_000L },
        )

        val state = manager.sync() as SyncState.Done
        assertEquals(1, state.summary.failedSections.size)
        assertTrue(state.summary.failedSections.all { it.courseCode == "C101" })
        assertEquals(1, state.summary.succeededSections.size)
        assertTrue(state.summary.succeededSections.all { it.courseCode == "C102" })

        val courses = repo.observeCoursesForLatestTerm().first()
        assertEquals(8, courses.first { it.courseCode == "C102" }.attended)
        assertEquals(null, courses.first { it.courseCode == "C101" }.attended)
    }

    @Test
    fun `session expired emits NeedsLogin when credentials missing or Failed when credentials exist`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        val client = FakePortalSyncClient(
            dashboardHandler = { FetchResult.SessionExpired("Login page returned") },
            htmlHandler = { FetchResult.Error("Unused") },
        )

        val needsLoginManager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.FIRST_LOGIN },
            requestDelayMs = 0L,
        )
        assertEquals(SyncState.NeedsLogin, needsLoginManager.sync())

        val failedManager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
        )
        val failed = failedManager.sync()
        assertTrue(failed is SyncState.Failed)
    }

    @Test
    fun `challenge needed emits NeedsChallenge without fetching portal`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        val client = FakePortalSyncClient(
            dashboardHandler = { FetchResult.Error("Should not be called") },
            htmlHandler = { FetchResult.Error("Should not be called") },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = {
                AuthSessionSnapshot(
                    state = SessionState.CHALLENGE_REQUIRED,
                    challengeReason = "CAPTCHA required",
                )
            },
            onSessionExpired = { SessionState.CHALLENGE_REQUIRED },
            requestDelayMs = 0L,
        )

        val result = manager.sync()
        assertEquals(SyncState.NeedsChallenge("CAPTCHA required"), result)
        assertEquals(0, client.dashboardCalls.get())
    }

    @Test
    fun `cancellation resets state to Idle and releases lock`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        val enteredFetch = CompletableDeferred<Unit>()
        val blocker = CompletableDeferred<Unit>()
        var blockOnDashboard = true

        val client = FakePortalSyncClient(
            dashboardHandler = {
                if (blockOnDashboard) {
                    enteredFetch.complete(Unit)
                    blocker.await()
                }
                FetchResult.Success(
                    dashboardHtml("77", "B.E, SEM 06, SEC A", listOf("C101" to "Course One")),
                    "https://parents.nie.ac.in/dashboard",
                )
            },
            htmlHandler = { url ->
                when {
                    url.contains("attendencelist") -> FetchResult.Success(attendanceHtml("C101", 4, 1), url)
                    url.contains("ciedetails") -> FetchResult.Success(cieHtml("C101", 80), url)
                    else -> FetchResult.Success(lessonHtml("C101"), url)
                }
            },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
        )

        var caughtCancel = false
        val job = launch {
            try {
                manager.sync()
            } catch (_: CancellationException) {
                caughtCancel = true
            }
        }
        enteredFetch.await()
        manager.cancel()
        job.join()

        assertTrue(caughtCancel)
        assertEquals(SyncState.Idle, manager.state.value)

        // Lock must be released so a subsequent sync succeeds.
        blockOnDashboard = false
        val next = manager.sync()
        assertTrue(next is SyncState.Done)
    }

    @Test
    fun `multiple terms preserve historical data and switch latest term`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        var currentSem = "77"
        var now = 1_000L

        val client = FakePortalSyncClient(
            dashboardHandler = {
                if (currentSem == "77") {
                    FetchResult.Success(
                        dashboardHtml("77", "B.E, SEM 06, SEC A", listOf("C101" to "Course One")),
                        "https://parents.nie.ac.in/dashboard",
                    )
                } else {
                    FetchResult.Success(
                        dashboardHtml("88", "B.E, SEM 07, SEC A", listOf("C201" to "New Sem Course")),
                        "https://parents.nie.ac.in/dashboard",
                    )
                }
            },
            htmlHandler = { url ->
                val code = if (currentSem == "77") "C101" else "C201"
                val present = if (currentSem == "77") 4 else 9
                val absent = 1
                val pct = if (currentSem == "77") 80 else 90
                when {
                    url.contains("attendencelist") -> FetchResult.Success(attendanceHtml(code, present, absent), url)
                    url.contains("ciedetails") -> FetchResult.Success(cieHtml(code, pct), url)
                    else -> FetchResult.Success(lessonHtml(code), url)
                }
            },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
            clock = { now },
        )

        manager.sync()
        currentSem = "88"
        now = 2_000L
        manager.sync(forceRefresh = true)

        val terms = repo.observeAllTerms().first()
        assertEquals(2, terms.size)
        assertEquals("88", repo.observeLatestTerm().first()?.semId)

        val sem77Courses = repo.observeCoursesForTerm("77").first()
        val sem88Courses = repo.observeCoursesForTerm("88").first()
        assertEquals(1, sem77Courses.size)
        assertEquals(4, sem77Courses.first().attended)
        assertEquals(1, sem88Courses.size)
        assertEquals(9, sem88Courses.first().attended)
    }

    @Test
    fun `newly discovered courses in existing term are stored automatically`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        var courses = listOf("C101" to "Course One")

        val client = FakePortalSyncClient(
            dashboardHandler = {
                FetchResult.Success(
                    dashboardHtml("77", "B.E, SEM 06, SEC A", courses),
                    "https://parents.nie.ac.in/dashboard",
                )
            },
            htmlHandler = { url ->
                val code = if (url.contains("courseId=1")) "C101" else "C102"
                when {
                    url.contains("attendencelist") -> FetchResult.Success(attendanceHtml(code, 4, 1), url)
                    url.contains("ciedetails") -> FetchResult.Success(cieHtml(code, 80), url)
                    else -> FetchResult.Success(lessonHtml(code), url)
                }
            },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
        )

        manager.sync()
        assertEquals(1, repo.observeCoursesForLatestTerm().first().size)

        courses = listOf("C101" to "Course One", "C102" to "Elective Course")
        val second = manager.sync(forceRefresh = true) as SyncState.Done
        assertEquals(2, second.summary.coursesDiscovered)
        assertEquals(listOf("C101", "C102"), repo.observeCoursesForLatestTerm().first().map { it.courseCode })
    }

    @Test
    fun `no duplicate simultaneous sync runs when sync is already in flight`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val client = FakePortalSyncClient(
            dashboardHandler = {
                entered.complete(Unit)
                release.await()
                FetchResult.Success(
                    dashboardHtml("77", "B.E, SEM 06, SEC A", listOf("C101" to "Course One")),
                    "https://parents.nie.ac.in/dashboard",
                )
            },
            htmlHandler = { url ->
                when {
                    url.contains("attendencelist") -> FetchResult.Success(attendanceHtml("C101", 4, 1), url)
                    url.contains("ciedetails") -> FetchResult.Success(cieHtml("C101", 80), url)
                    else -> FetchResult.Success(lessonHtml("C101"), url)
                }
            },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
        )

        val firstJob = async { manager.sync() }
        entered.await()
        val duplicateReturn = manager.sync(forceRefresh = true)
        assertTrue(duplicateReturn is SyncState.Running)

        release.complete(Unit)
        val firstResult = firstJob.await()
        assertTrue(firstResult is SyncState.Done)
        assertEquals(1, client.dashboardCalls.get())
    }

    @Test
    fun `five minute throttle skips automatic sync unless forceRefresh is true`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        var now = 10_000L
        val client = FakePortalSyncClient(
            dashboardHandler = {
                FetchResult.Success(
                    dashboardHtml("77", "B.E, SEM 06, SEC A", listOf("C101" to "Course One")),
                    "https://parents.nie.ac.in/dashboard",
                )
            },
            htmlHandler = { url ->
                when {
                    url.contains("attendencelist") -> FetchResult.Success(attendanceHtml("C101", 4, 1), url)
                    url.contains("ciedetails") -> FetchResult.Success(cieHtml("C101", 80), url)
                    else -> FetchResult.Success(lessonHtml("C101"), url)
                }
            },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
            clock = { now },
        )

        manager.sync(forceRefresh = false)
        assertEquals(1, client.dashboardCalls.get())

        now += 60_000L // 1 minute later: throttled
        manager.sync(forceRefresh = false)
        assertEquals(1, client.dashboardCalls.get())

        // Manual refresh bypasses 5-minute throttle
        manager.sync(forceRefresh = true)
        assertEquals(2, client.dashboardCalls.get())
    }

    @Test
    fun `exam history syncs separately at most once per day`() = runTest {
        val repo = AttendanceRepository(InMemorySyncDao())
        var now = 100_000L
        val client = FakePortalSyncClient(
            dashboardHandler = { FetchResult.Error("Unused") },
            htmlHandler = { FetchResult.Error("Unused") },
            examHistoryHandler = { FetchResult.Success(examHistoryHtml(), "https://parents.nie.ac.in/results") },
        )
        val manager = SyncManager(
            syncClient = client,
            repository = repo,
            ensureSession = { AuthSessionSnapshot(state = SessionState.SESSION_VALID) },
            onSessionExpired = { SessionState.SESSION_EXPIRED },
            requestDelayMs = 0L,
            clock = { now },
        )

        val first = manager.syncExamHistory("<USN>")
        assertEquals(ExamHistorySyncResult.Synced(1), first)
        assertEquals(1, client.examHistoryCalls.get())

        now += 60 * 60 * 1000L // 1 hour later -> skipped
        val second = manager.syncExamHistory("<USN>")
        assertEquals(ExamHistorySyncResult.SkippedRecentlySynced, second)
        assertEquals(1, client.examHistoryCalls.get())

        now += 24 * 60 * 60 * 1000L // 24+ hours later -> allowed
        val third = manager.syncExamHistory("<USN>")
        assertEquals(ExamHistorySyncResult.Synced(1), third)
        assertEquals(2, client.examHistoryCalls.get())
    }
}
