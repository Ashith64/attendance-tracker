package app.attendancetracker.debug

import androidx.compose.runtime.Composable
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.ui.CourseDetailUiModel
import app.attendancetracker.ui.HomeUiState

/**
 * Release no-op implementation of [DebugSupport].
 * FakeRepository and DebugScreen are excluded from release builds.
 */
object DebugBridge : DebugSupport {
    override val isDebugAvailable: Boolean = false
    override val useFakeData: Boolean = false

    override fun setUseFakeData(enabled: Boolean) = Unit

    override fun getFakeHomeUiState(thresholdPercent: Int, isSyncing: Boolean): HomeUiState =
        HomeUiState()

    override fun getFakeCourseDetail(courseCode: String, thresholdPercent: Int): CourseDetailUiModel? =
        null

    @Composable
    override fun DebugScreenContent(
        repository: AttendanceRepository,
        onBack: () -> Unit,
    ) = Unit
}
