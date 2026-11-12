package app.attendancetracker.ui

import app.attendancetracker.data.AttendancePayload
import app.attendancetracker.data.CourseAttendanceUiModel
import app.attendancetracker.data.SnapshotEntity
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.engine.AttendanceEngine
import app.attendancetracker.engine.AttendanceInput
import app.attendancetracker.engine.AttendanceResult
import app.attendancetracker.engine.AttendanceStatus
import app.attendancetracker.parser.CieData
import app.attendancetracker.parser.LessonPlanData
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

enum class CourseHealth {
    SAFE,
    BELOW,
    NO_DATA,
}

data class CourseCardUiModel(
    val courseCode: String,
    val courseName: String,
    val term: String,
    val attended: Int?,
    val conducted: Int?,
    val missed: Int?,
    val unit: String,
    val percentageText: String,
    val guidanceText: String,
    val statusLineText: String,
    val health: CourseHealth,
    val updatedAgoText: String,
    val lastSyncedAt: Long?,
    val isStale: Boolean,
    val isSuspect: Boolean,
    val latestErrorMessage: String?,
    val engineResult: AttendanceResult?,
)

sealed class DashboardScreenState {
    data object Content : DashboardScreenState()
    data object Empty : DashboardScreenState()
    data object Loading : DashboardScreenState()
    data class Error(val message: String) : DashboardScreenState()
    data class NeedsChallenge(val reason: String?) : DashboardScreenState()
}

data class HomeUiState(
    val studentName: String? = null,
    val termLabel: String? = null,
    val lastSyncText: String = "Not synced yet",
    val isSyncing: Boolean = false,
    val syncProgress: Float? = null,
    val courses: List<CourseCardUiModel> = emptyList(),
    val screenState: DashboardScreenState = DashboardScreenState.Empty,
    val thresholdPercent: Int = 75,
    val layoutChangedWarning: Boolean = false,
    val bannerMessage: String? = null,
)

data class AttendanceHistoryEntry(
    val id: Long,
    val fetchedAt: Long,
    val timestampLabel: String,
    val attended: Int?,
    val conducted: Int?,
    val percentageText: String,
    val status: SnapshotStatus,
    val errorMessage: String? = null,
)

data class CourseDetailUiModel(
    val card: CourseCardUiModel,
    val attendanceHistory: List<AttendanceHistoryEntry>,
    val cieData: CieData?,
    val lessonPlan: LessonPlanData?,
)

enum class BunkMode {
    MISS,
    ATTEND,
}

data class BunkSimulationUiModel(
    val mode: BunkMode,
    val count: Int,
    val thresholdPercent: Int,
    val projectedAttended: Int,
    val projectedConducted: Int,
    val projectedPercentageText: String,
    val projectedStatusText: String,
    val projectedHealth: CourseHealth,
)

object UiStateMapper {

    private val json = Json { ignoreUnknownKeys = true }

    fun formatRelativeTime(timestampMs: Long?, nowMs: Long): String {
        if (timestampMs == null || timestampMs <= 0L) return "never synced"
        val diffMs = (nowMs - timestampMs).coerceAtLeast(0L)
        val minutes = diffMs / 60_000L
        val hours = diffMs / 3_600_000L
        val days = diffMs / 86_400_000L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes m ago"
            hours < 24L -> "$hours h ago"
            else -> "$days d ago"
        }
    }

    fun mapCourseCard(
        model: CourseAttendanceUiModel,
        thresholdPercent: Int,
        nowMs: Long,
        unit: String = "classes",
    ): CourseCardUiModel {
        val attended = model.attended
        val conducted = model.conducted
        val result = model.engineResult ?: if (attended != null && conducted != null) {
            AttendanceEngine.calculate(
                AttendanceInput(
                    attended = attended,
                    conducted = conducted,
                    thresholdPercent = thresholdPercent,
                ),
            )
        } else {
            null
        }

        val hasData = conducted != null && conducted > 0 && attended != null && result != null &&
            result.status != AttendanceStatus.NO_DATA && result.status != AttendanceStatus.INVALID

        val health = when {
            !hasData -> CourseHealth.NO_DATA
            result?.status == AttendanceStatus.SAFE -> CourseHealth.SAFE
            result?.status == AttendanceStatus.BELOW -> CourseHealth.BELOW
            else -> CourseHealth.NO_DATA
        }

        val percentageText = if (!hasData) {
            "No data"
        } else {
            "${result.percentage.roundToInt()}%"
        }

        val missed = if (hasData) {
            (conducted - attended).coerceAtLeast(0)
        } else {
            null
        }

        val guidanceText = when {
            !hasData -> "No classes conducted yet"
            result.status == AttendanceStatus.SAFE && result.canMiss > 0 ->
                "can miss ${result.canMiss}"
            result.status == AttendanceStatus.SAFE ->
                "on target"
            result.recoveryImpossible ->
                "recovery not possible at $thresholdPercent%"
            else ->
                "attend ${result.mustAttendToRecover} more"
        }

        val statusLineText = when (health) {
            CourseHealth.SAFE -> "Above $thresholdPercent%"
            CourseHealth.BELOW -> "Below $thresholdPercent%"
            CourseHealth.NO_DATA -> "No data"
        }

        val relative = formatRelativeTime(model.lastSyncedAt, nowMs)
        val updatedAgoText = if (model.lastSyncedAt == null) "not updated" else "updated $relative"

        return CourseCardUiModel(
            courseCode = model.courseCode,
            courseName = model.courseName,
            term = model.term,
            attended = attended,
            conducted = conducted,
            missed = missed,
            unit = unit,
            percentageText = percentageText,
            guidanceText = guidanceText,
            statusLineText = statusLineText,
            health = health,
            updatedAgoText = updatedAgoText,
            lastSyncedAt = model.lastSyncedAt,
            isStale = model.isStale,
            isSuspect = model.isSuspect,
            latestErrorMessage = model.latestErrorMessage,
            engineResult = result,
        )
    }

    fun mapAttendanceHistory(
        snapshots: List<SnapshotEntity>,
        nowMs: Long,
    ): List<AttendanceHistoryEntry> = snapshots.map { snap ->
        val payload = snap.payloadJson?.let { raw ->
            runCatching { json.decodeFromString(AttendancePayload.serializer(), raw) }.getOrNull()
        }
        val pct = if (payload != null && payload.conducted > 0) {
            "${((payload.attended * 100.0) / payload.conducted).roundToInt()}%"
        } else {
            "No data"
        }
        AttendanceHistoryEntry(
            id = snap.id,
            fetchedAt = snap.fetchedAt,
            timestampLabel = formatRelativeTime(snap.fetchedAt, nowMs),
            attended = payload?.attended,
            conducted = payload?.conducted,
            percentageText = pct,
            status = snap.status,
            errorMessage = snap.errorMessage,
        )
    }

    fun decodeCieData(snapshot: SnapshotEntity?): CieData? {
        val raw = snapshot?.payloadJson ?: return null
        return runCatching { json.decodeFromString(CieData.serializer(), raw) }.getOrNull()
    }

    fun decodeLessonPlanData(snapshot: SnapshotEntity?): LessonPlanData? {
        val raw = snapshot?.payloadJson ?: return null
        return runCatching { json.decodeFromString(LessonPlanData.serializer(), raw) }.getOrNull()
    }

    fun simulateBunk(
        attended: Int,
        conducted: Int,
        thresholdPercent: Int,
        mode: BunkMode,
        count: Int,
    ): BunkSimulationUiModel {
        val safeCount = count.coerceAtLeast(0)
        val input = AttendanceInput(
            attended = attended.coerceAtLeast(0),
            conducted = conducted.coerceAtLeast(0),
            thresholdPercent = thresholdPercent,
        )
        val result = when (mode) {
            BunkMode.MISS -> AttendanceEngine.simulateAbsences(input, safeCount)
            BunkMode.ATTEND -> AttendanceEngine.simulateAttendance(input, safeCount)
        }
        val projectedAttended = when (mode) {
            BunkMode.MISS -> input.attended
            BunkMode.ATTEND -> input.attended + safeCount
        }
        val projectedConducted = input.conducted + safeCount

        val health = when (result.status) {
            AttendanceStatus.SAFE -> CourseHealth.SAFE
            AttendanceStatus.BELOW -> CourseHealth.BELOW
            AttendanceStatus.NO_DATA, AttendanceStatus.INVALID -> CourseHealth.NO_DATA
        }
        val pctText = if (projectedConducted == 0 || health == CourseHealth.NO_DATA) {
            "No data"
        } else {
            "${result.percentage.roundToInt()}%"
        }
        val statusText = when {
            health == CourseHealth.NO_DATA -> "No classes conducted"
            result.status == AttendanceStatus.SAFE && result.canMiss > 0 ->
                "Above $thresholdPercent% · can miss ${result.canMiss}"
            result.status == AttendanceStatus.SAFE ->
                "On target ($thresholdPercent%)"
            result.recoveryImpossible ->
                "Below $thresholdPercent% · recovery not possible"
            else ->
                "Below $thresholdPercent% · attend ${result.mustAttendToRecover} more"
        }

        return BunkSimulationUiModel(
            mode = mode,
            count = safeCount,
            thresholdPercent = thresholdPercent,
            projectedAttended = projectedAttended,
            projectedConducted = projectedConducted,
            projectedPercentageText = pctText,
            projectedStatusText = statusText,
            projectedHealth = health,
        )
    }
}
