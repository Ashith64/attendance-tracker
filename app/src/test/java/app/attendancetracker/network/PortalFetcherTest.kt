package app.attendancetracker.network

import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.data.CourseEntity
import app.attendancetracker.data.ExamHistoryEntity
import app.attendancetracker.data.PortalDao
import app.attendancetracker.data.SnapshotCategory
import app.attendancetracker.data.SnapshotEntity
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.data.TermEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private class FetcherTestDao : PortalDao {
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
}

class PortalFetcherTest {

    private fun fakeClient(
        jar: PersistentCookieJar,
        handler: (String, String?) -> Pair<Int, String>,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .addInterceptor(
            Interceptor { chain ->
                val req = chain.request()
                val cookies = jar.loadForRequest(req.url)
                    .joinToString("; ") { "${it.name}=${it.value}" }
                    .ifEmpty { null }
                val (code, body) = handler(req.url.toString(), cookies)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            },
        )
        .build()

    private fun dashboardHtml(): String = """
        <html><body>
          <div class="cn-stu-data1"><p>B.E, SEM 06, SEC A</p></div>
          <table class="cn-pay-table"><tbody>
            <tr>
              <td>C101</td><td>Course One</td>
              <td><a href="index.php?task=getuploadlession&courseId=1&secId=&semId=77">Lesson</a></td>
              <td><a href="index.php?task=attendencelist&courseId=1&secId=&semId=77">Attendance</a></td>
              <td><a href="index.php?task=ciedetails&courseId=1&secId=&semId=77">CIE</a></td>
            </tr>
            <tr>
              <td>C102</td><td>Course Two</td>
              <td><a href="index.php?task=getuploadlession&courseId=2&secId=9&semId=77">Lesson</a></td>
              <td><a href="index.php?task=attendencelist&courseId=2&secId=9&semId=77">Attendance</a></td>
              <td><a href="index.php?task=ciedetails&courseId=2&secId=9&semId=77">CIE</a></td>
            </tr>
          </tbody></table>
        </body></html>
    """.trimIndent()

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

    @Test
    fun `syncAll forwards cookies isolates failures flags suspect CIE and stores exam history`() = runTest {
        val baseUrl = "https://parents.nie.ac.in/"
        val observedCookies = mutableListOf<String?>()
        val jar = PersistentCookieJar()
        jar.importFromWebViewCookieHeader(baseUrl, "session_id=TEST_COOKIE_PLACEHOLDER")

        val client = fakeClient(jar) { path, cookie ->
            observedCookies.add(cookie)
            when {
                path.contains("task=dashboard") -> 200 to dashboardHtml()
                path.contains("task=attendencelist&courseId=1") -> 200 to attendanceHtml("C101", 4, 1) // 80%
                path.contains("task=ciedetails&courseId=1") -> 200 to cieHtml("C101", 80) // matches 80% -> OK
                path.contains("task=getuploadlession&courseId=1") -> 200 to lessonHtml("C101")
                path.contains("task=attendencelist&courseId=2") -> 200 to attendanceHtml("C102", 4, 1) // 80%
                path.contains("task=ciedetails&courseId=2") -> 200 to cieHtml("C102", 50) // 50% vs 80% -> SUSPECT
                path.contains("task=getuploadlession&courseId=2") -> 500 to "Server Error" // isolated failure
                path.contains("task=getResult") -> 200 to """
                    <html><body><div class="result-table">
                      <div class="cn-blocks"><h6>Even 2026 - Semester : 6 <span class="cn-bgcolor1">SGPA: 9.0</span></h6></div>
                      <table class="res-table"><tbody><tr><td>C101</td><td>Course One</td><td>4</td><td>4</td><td>9</td><td>A+</td></tr></tbody></table>
                    </div></body></html>
                """.trimIndent()
                else -> 404 to "Not found"
            }
        }

        val fetcher = PortalFetcher(cookieJar = jar, baseUrl = baseUrl, client = client, requestDelayMs = 0L)
        val repo = AttendanceRepository(FetcherTestDao())

        val summary = fetcher.syncAll(repository = repo, fetchExamHistoryForUsn = "TEST_USN")
        assertFalse(summary.sessionExpired)
        assertEquals("77", summary.semId)
        assertEquals(2, summary.coursesDiscovered)
        assertEquals(2, summary.attendanceOk)
        assertEquals(1, summary.cieOk)
        assertEquals(1, summary.cieSuspect)
        assertEquals(1, summary.lessonPlanOk)
        assertEquals(1, summary.lessonPlanFailed)
        assertTrue(summary.examHistorySynced)

        assertTrue(observedCookies.all { it?.contains("session_id=TEST_COOKIE_PLACEHOLDER") == true })
        assertEquals(SnapshotStatus.SUSPECT, repo.latestAttemptStatus("C102", SnapshotCategory.CIE, "77"))
        assertEquals(SnapshotStatus.FAILED, repo.latestAttemptStatus("C102", SnapshotCategory.LESSON_PLAN, "77"))
        assertEquals(1, repo.observeExamHistory().first().size)
    }

    @Test
    fun `syncAll stops immediately when session expires`() = runTest {
        val requestCount = AtomicInteger(0)
        val jar = PersistentCookieJar()
        val client = fakeClient(jar) { _, _ ->
            requestCount.incrementAndGet()
            200 to """<html><body><form id="login-form"><input type="hidden" name="task" value="loginOtp"></form></body></html>"""
        }
        val fetcher = PortalFetcher(cookieJar = jar, baseUrl = "https://parents.nie.ac.in/", client = client, requestDelayMs = 0L)
        val repo = AttendanceRepository(FetcherTestDao())
        val summary = fetcher.syncAll(repository = repo)
        assertTrue(summary.sessionExpired)
        assertEquals(1, requestCount.get())
    }

    @Test
    fun `fetchHtml retries network IOException with exponential backoff`() = runTest {
        val attempts = AtomicInteger(0)
        val jar = PersistentCookieJar()
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .addInterceptor(
                Interceptor { chain ->
                    if (attempts.incrementAndGet() < 3) {
                        throw IOException("Simulated transient network drop")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html><body>OK</body></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                },
            )
            .build()

        val fetcher = PortalFetcher(
            cookieJar = jar,
            baseUrl = "https://parents.nie.ac.in/",
            client = client,
            maxNetworkRetries = 2,
            initialBackoffMs = 1L,
        )
        val result = fetcher.fetchDashboard()
        assertTrue(result is FetchResult.Success)
        assertEquals(3, attempts.get())
    }
}
