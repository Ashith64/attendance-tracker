package app.attendancetracker.debug

import androidx.compose.runtime.Composable
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.ui.CourseDetailUiModel
import app.attendancetracker.ui.HomeUiState

/**
 * Abstraction for debug-only tooling (FakeRepository flag and Debug Inspector screen).
 * Release builds bind a no-op implementation with zero fake data or debug UI compiled in.
 */
interface DebugSupport {
    val isDebugAvailable: Boolean
    val useFakeData: Boolean
    fun setUseFakeData(enabled: Boolean)
    fun getFakeHomeUiState(thresholdPercent: Int, isSyncing: Boolean): HomeUiState
    fun getFakeCourseDetail(courseCode: String, thresholdPercent: Int): CourseDetailUiModel?

    @Composable
    fun DebugScreenContent(
        repository: AttendanceRepository,
        onBack: () -> Unit,
    )
}
