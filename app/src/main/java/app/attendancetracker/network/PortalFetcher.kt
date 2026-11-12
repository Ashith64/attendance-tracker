package app.attendancetracker.network

import android.util.Log
import app.attendancetracker.data.AttendancePayload
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.data.ExamHistoryEntity
import app.attendancetracker.data.SnapshotCategory
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.data.UrlSanitizer
import app.attendancetracker.parser.ParseResult
import app.attendancetracker.parser.PortalParsers
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

sealed class FetchResult<out T> {
    data class Success<T>(val data: T, val finalUrl: String) : FetchResult<T>()
    data class SessionExpired(val reason: String) : FetchResult<Nothing>()
    data class Error(val reason: String, val isNetworkError: Boolean = false) : FetchResult<Nothing>()
}

interface PortalSyncClient {
    suspend fun fetchDashboard(): FetchResult<String>
    suspend fun fetchHtml(portalUrl: String): FetchResult<String>
    suspend fun fetchExamHistory(usn: String): FetchResult<String>
}

data class PortalSyncSummary(
    val semId: String?,
    val coursesDiscovered: Int,
    val attendanceOk: Int,
    val attendanceFailed: Int,
    val cieOk: Int,
    val cieSuspect: Int,
    val cieFailed: Int,
    val lessonPlanOk: Int,
    val lessonPlanFailed: Int,
    val examHistorySynced: Boolean,
    val sessionExpired: Boolean,
    val errorMessage: String? = null,
)

/**
 * Architecture B HTTP fetcher (OkHttp + PersistentCookieJar + Jsoup parsers).
 * - 20-second timeouts
 * - Exponential backoff retry on network IOException only
 * - Preserves portal-provided course links verbatim
 * - Never logs cookies, request bodies, credentials, or tokens
 */
class PortalFetcher(
    val cookieJar: PersistentCookieJar,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val client: OkHttpClient = buildDefaultClient(cookieJar),
    private val maxNetworkRetries: Int = 2,
    private val initialBackoffMs: Long = 300L,
    private val requestDelayMs: Long = 350L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val userAgent: String = DEFAULT_BROWSER_USER_AGENT,
) : PortalSyncClient {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val DEFAULT_BASE_URL = "https://parents.nie.ac.in/"
        const val DASHBOARD_PATH = "index.php?option=com_studentdashboard&controller=studentdashboard&task=dashboard"
        const val EXAM_HISTORY_PATH_PREFIX = "index.php?option=com_history&task=getResult&usn="
        const val DEFAULT_BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun buildDefaultClient(cookieJar: PersistentCookieJar): OkHttpClient =
            OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectionPool(ConnectionPool(10, 60, TimeUnit.SECONDS))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .build()
    }

    internal fun resolveUrl(portalUrl: String): String {
        val trimmed = portalUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid baseUrl: $baseUrl")
        return base.resolve(trimmed)?.toString()
            ?: throw IllegalArgumentException("Cannot resolve portal URL")
    }

    override suspend fun fetchDashboard(): FetchResult<String> = fetchHtml(DASHBOARD_PATH)

    override suspend fun fetchExamHistory(usn: String): FetchResult<String> =
        fetchHtml("$EXAM_HISTORY_PATH_PREFIX${java.net.URLEncoder.encode(usn.trim(), "UTF-8")}")

    override suspend fun fetchHtml(portalUrl: String): FetchResult<String> {
        val resolved = try {
            resolveUrl(portalUrl)
        } catch (e: IllegalArgumentException) {
            return FetchResult.Error("Invalid URL: ${e.message}")
        }

        var attempt = 0
        var backoff = initialBackoffMs
        while (true) {
            try {
                val request = Request.Builder()
                    .url(resolved)
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-User", "?1")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Referer", "https://parents.nie.ac.in/index.php")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    val code = response.code
                    if (!response.isSuccessful) {
                        Log.e("PortalFetcher", "HTTP error $code for ${UrlSanitizer.sanitize(resolved)} (finalUrl=${UrlSanitizer.sanitize(finalUrl)})")
                        return FetchResult.Error("HTTP ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    val isLogin = looksLikeLoginPage(body, finalUrl)
                    Log.d("PortalFetcher", "fetchHtml: code=$code, finalUrl=${UrlSanitizer.sanitize(finalUrl)}, bodyLength=${body.length}, isLogin=$isLogin")
                    if (isLogin) {
                        return FetchResult.SessionExpired("Portal returned login page")
                    }
                    return FetchResult.Success(body, finalUrl)
                }
            } catch (ioe: IOException) {
                if (attempt >= maxNetworkRetries) {
                    return FetchResult.Error(
                        reason = ioe.message ?: "Network error",
                        isNetworkError = true,
                    )
                }
                delay(backoff)
                backoff *= 2
                attempt++
            }
        }
    }

    private fun looksLikeLoginPage(html: String, finalUrl: String): Boolean {
        // Only return true when the actual login form (Stage 1 or Stage 2) is present.
        // Every authenticated page includes a hidden logout form with name="log" and option="com_user",
        // so checking broad strings like name="task" value="login" or com_user would falsely trigger.
        if (html.contains("id=\"login-form\"", ignoreCase = true) ||
            html.contains("id=\"id-type-select\"", ignoreCase = true) ||
            html.contains("inputselectday", ignoreCase = true) ||
            html.contains("name=\"task\" value=\"loginOtp\"", ignoreCase = true) ||
            (html.contains("name=\"task\" value=\"login\"", ignoreCase = true) && html.contains("name=\"idType\"", ignoreCase = true))
        ) {
            return true
        }
        val lowerUrl = finalUrl.lowercase()
        return lowerUrl.contains("task=loginotp") ||
            (lowerUrl.contains("task=login") && !lowerUrl.contains("task=dashboard"))
    }

    /**
     * Executes the Step 8 sequential sync flow:
     * A. Dashboard -> discover term & courses
     * B. Per-course Attendance
     * C. Per-course CIE (cross-checked against Attendance %)
     * D. Per-course Lesson Plan
     * E. Exam History (when [fetchExamHistoryForUsn] is non-null)
     *
     * Stops immediately on expired session; isolates per-course/per-category failures.
     */
    suspend fun syncAll(
        repository: AttendanceRepository,
        fetchExamHistoryForUsn: String? = null,
    ): PortalSyncSummary {
        var attOk = 0
        var attFail = 0
        var cieOk = 0
        var cieSuspect = 0
        var cieFail = 0
        var lpOk = 0
        var lpFail = 0
        var examSynced = false

        // A. Dashboard
        val dashRes = fetchDashboard()
        if (dashRes is FetchResult.SessionExpired) {
            return PortalSyncSummary(null, 0, 0, 0, 0, 0, 0, 0, 0, false, true, dashRes.reason)
        }
        if (dashRes is FetchResult.Error) {
            return PortalSyncSummary(null, 0, 0, 0, 0, 0, 0, 0, 0, false, false, dashRes.reason)
        }
        val dashSuccess = dashRes as FetchResult.Success
        val courseListResult = PortalParsers.parseCourseList(dashSuccess.data)
        if (courseListResult is ParseResult.Failure) {
            return PortalSyncSummary(null, 0, 0, 0, 0, 0, 0, 0, 0, false, false, courseListResult.reason)
        }
        val courseList = (courseListResult as ParseResult.Success).data
        val termId = courseList.semId
        repository.recordTermAndCourses(
            semId = termId,
            semesterNumber = courseList.semesterNumber,
            label = courseList.termLabel,
            courses = courseList.courses.map { it.code to it.name },
            now = clock(),
        )

        // B, C, D per course sequentially with polite pause
        for (course in courseList.courses) {
            var computedAttPct: Int? = null

            // B. Attendance
            val attUrl = course.attendanceUrl
            if (attUrl != null) {
                if (requestDelayMs > 0) delay(requestDelayMs)
                when (val attFetch = fetchHtml(attUrl)) {
                    is FetchResult.SessionExpired -> {
                        return PortalSyncSummary(
                            termId, courseList.courses.size,
                            attOk, attFail, cieOk, cieSuspect, cieFail, lpOk, lpFail,
                            examSynced, true, attFetch.reason,
                        )
                    }
                    is FetchResult.Error -> {
                        attFail++
                        repository.recordSnapshot(
                            courseCode = course.code,
                            category = SnapshotCategory.ATTENDANCE,
                            term = termId,
                            status = SnapshotStatus.FAILED,
                            payloadJson = null,
                            errorMessage = attFetch.reason,
                            sourceUrl = attUrl,
                            fetchedAt = clock(),
                        )
                    }
                    is FetchResult.Success -> {
                        when (val parsed = PortalParsers.parseAttendance(attFetch.data, course.code)) {
                            is ParseResult.Failure -> {
                                attFail++
                                repository.recordSnapshot(
                                    courseCode = course.code,
                                    category = SnapshotCategory.ATTENDANCE,
                                    term = termId,
                                    status = SnapshotStatus.FAILED,
                                    payloadJson = null,
                                    errorMessage = parsed.reason,
                                    sourceUrl = attUrl,
                                    fetchedAt = clock(),
                                )
                            }
                            is ParseResult.Success -> {
                                attOk++
                                val d = parsed.data
                                if (d.conductedCount > 0) {
                                    computedAttPct = ((d.presentCount.toDouble() * 100.0) / d.conductedCount).roundToInt()
                                }
                                val payload = json.encodeToString(
                                    AttendancePayload(
                                        attended = d.presentCount,
                                        conducted = d.conductedCount,
                                        stillToGo = d.stillToGoCount,
                                        unit = "classes",
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
                            }
                        }
                    }
                }
            }

            // C. CIE
            val cieUrl = course.cieUrl
            if (cieUrl != null) {
                if (requestDelayMs > 0) delay(requestDelayMs)
                when (val cieFetch = fetchHtml(cieUrl)) {
                    is FetchResult.SessionExpired -> {
                        return PortalSyncSummary(
                            termId, courseList.courses.size,
                            attOk, attFail, cieOk, cieSuspect, cieFail, lpOk, lpFail,
                            examSynced, true, cieFetch.reason,
                        )
                    }
                    is FetchResult.Error -> {
                        cieFail++
                        repository.recordSnapshot(
                            courseCode = course.code,
                            category = SnapshotCategory.CIE,
                            term = termId,
                            status = SnapshotStatus.FAILED,
                            payloadJson = null,
                            errorMessage = cieFetch.reason,
                            sourceUrl = cieUrl,
                            fetchedAt = clock(),
                        )
                    }
                    is FetchResult.Success -> {
                        when (val parsed = PortalParsers.parseCie(cieFetch.data, course.code)) {
                            is ParseResult.Failure -> {
                                cieFail++
                                repository.recordSnapshot(
                                    courseCode = course.code,
                                    category = SnapshotCategory.CIE,
                                    term = termId,
                                    status = SnapshotStatus.FAILED,
                                    payloadJson = null,
                                    errorMessage = parsed.reason,
                                    sourceUrl = cieUrl,
                                    fetchedAt = clock(),
                                )
                            }
                            is ParseResult.Success -> {
                                val cieData = parsed.data
                                val cieAttPct = cieData.attendancePercent
                                val attMismatch = computedAttPct != null &&
                                    cieAttPct != null &&
                                    abs(cieAttPct - computedAttPct) > 1
                                val payload = json.encodeToString(cieData)
                                if (attMismatch) {
                                    cieSuspect++
                                    repository.recordSnapshot(
                                        courseCode = course.code,
                                        category = SnapshotCategory.CIE,
                                        term = termId,
                                        status = SnapshotStatus.SUSPECT,
                                        payloadJson = null,
                                        errorMessage = "SUSPECT: CIE attendance $cieAttPct% disagrees with rows $computedAttPct%",
                                        sourceUrl = cieUrl,
                                        fetchedAt = clock(),
                                    )
                                } else {
                                    cieOk++
                                    repository.recordSnapshot(
                                        courseCode = course.code,
                                        category = SnapshotCategory.CIE,
                                        term = termId,
                                        status = SnapshotStatus.OK,
                                        payloadJson = payload,
                                        errorMessage = cieData.discrepancyNote,
                                        sourceUrl = cieUrl,
                                        fetchedAt = clock(),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // D. Lesson Plan
            val lpUrl = course.lessonPlanUrl
            if (lpUrl != null) {
                if (requestDelayMs > 0) delay(requestDelayMs)
                when (val lpFetch = fetchHtml(lpUrl)) {
                    is FetchResult.SessionExpired -> {
                        return PortalSyncSummary(
                            termId, courseList.courses.size,
                            attOk, attFail, cieOk, cieSuspect, cieFail, lpOk, lpFail,
                            examSynced, true, lpFetch.reason,
                        )
                    }
                    is FetchResult.Error -> {
                        lpFail++
                        repository.recordSnapshot(
                            courseCode = course.code,
                            category = SnapshotCategory.LESSON_PLAN,
                            term = termId,
                            status = SnapshotStatus.FAILED,
                            payloadJson = null,
                            errorMessage = lpFetch.reason,
                            sourceUrl = lpUrl,
                            fetchedAt = clock(),
                        )
                    }
                    is FetchResult.Success -> {
                        when (val parsed = PortalParsers.parseLessonPlan(lpFetch.data, course.code)) {
                            is ParseResult.Failure -> {
                                lpFail++
                                repository.recordSnapshot(
                                    courseCode = course.code,
                                    category = SnapshotCategory.LESSON_PLAN,
                                    term = termId,
                                    status = SnapshotStatus.FAILED,
                                    payloadJson = null,
                                    errorMessage = parsed.reason,
                                    sourceUrl = lpUrl,
                                    fetchedAt = clock(),
                                )
                            }
                            is ParseResult.Success -> {
                                lpOk++
                                repository.recordSnapshot(
                                    courseCode = course.code,
                                    category = SnapshotCategory.LESSON_PLAN,
                                    term = termId,
                                    status = SnapshotStatus.OK,
                                    payloadJson = json.encodeToString(parsed.data),
                                    errorMessage = null,
                                    sourceUrl = lpUrl,
                                    fetchedAt = clock(),
                                )
                            }
                        }
                    }
                }
            }
        }

        // E. Exam History (optional, daily / on-demand)
        if (!fetchExamHistoryForUsn.isNullOrBlank()) {
            if (requestDelayMs > 0) delay(requestDelayMs)
            when (val examFetch = fetchExamHistory(fetchExamHistoryForUsn)) {
                is FetchResult.SessionExpired -> {
                    return PortalSyncSummary(
                        termId, courseList.courses.size,
                        attOk, attFail, cieOk, cieSuspect, cieFail, lpOk, lpFail,
                        false, true, examFetch.reason,
                    )
                }
                is FetchResult.Success -> {
                    val parsed = PortalParsers.parseExamHistory(examFetch.data)
                    if (parsed is ParseResult.Success) {
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
                                    sourceUrl = examFetch.finalUrl,
                                )
                            },
                        )
                        examSynced = true
                    }
                }
                is FetchResult.Error -> Unit
            }
        }

        return PortalSyncSummary(
            semId = termId,
            coursesDiscovered = courseList.courses.size,
            attendanceOk = attOk,
            attendanceFailed = attFail,
            cieOk = cieOk,
            cieSuspect = cieSuspect,
            cieFailed = cieFail,
            lessonPlanOk = lpOk,
            lessonPlanFailed = lpFail,
            examHistorySynced = examSynced,
            sessionExpired = false,
        )
    }
}
