package app.attendancetracker.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.attendancetracker.BuildConfig
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.ui.CourseDetailUiModel
import app.attendancetracker.ui.FakeRepository
import app.attendancetracker.ui.HomeUiState

/**
 * Debug-only implementation of [DebugSupport].
 * Provides access to the hidden [DebugScreen] and the [FakeRepository] debug flag.
 */
object DebugBridge : DebugSupport {
    override val isDebugAvailable: Boolean = BuildConfig.DEBUG

    private var _useFakeData by mutableStateOf(false)
    override val useFakeData: Boolean
        get() = _useFakeData

    override fun setUseFakeData(enabled: Boolean) {
        _useFakeData = enabled
    }

    override fun getFakeHomeUiState(thresholdPercent: Int, isSyncing: Boolean): HomeUiState =
        FakeRepository(System.currentTimeMillis()).getHomeUiState(
            thresholdPercent = thresholdPercent,
            isSyncing = isSyncing,
        )

    override fun getFakeCourseDetail(courseCode: String, thresholdPercent: Int): CourseDetailUiModel? =
        FakeRepository(System.currentTimeMillis()).getCourseDetail(
            courseCode = courseCode,
            thresholdPercent = thresholdPercent,
        )

    @Composable
    override fun DebugScreenContent(
        repository: AttendanceRepository,
        onBack: () -> Unit,
    ) {
        DebugScreen(
            repository = repository,
            useFakeData = useFakeData,
            onToggleFakeData = { setUseFakeData(it) },
            onBack = onBack,
        )
    }
}
