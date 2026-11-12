package app.attendancetracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.attendancetracker.ui.theme.AttendanceTrackerTheme

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewHomeContent() {
    AttendanceTrackerTheme {
        HomeScreen(
            state = FakeRepository().getHomeUiState(),
            onRefresh = {},
            onOpenSettings = {},
            onCourseClick = {},
            onOpenChallenge = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewHomeSyncing() {
    AttendanceTrackerTheme {
        HomeScreen(
            state = FakeRepository().getHomeUiState(
                isSyncing = true,
                layoutChangedWarning = true,
            ),
            onRefresh = {},
            onOpenSettings = {},
            onCourseClick = {},
            onOpenChallenge = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewHomeEmpty() {
    AttendanceTrackerTheme {
        HomeScreen(
            state = FakeRepository().getHomeUiState(
                screenState = DashboardScreenState.Empty,
            ),
            onRefresh = {},
            onOpenSettings = {},
            onCourseClick = {},
            onOpenChallenge = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewCourseDetailSafe() {
    AttendanceTrackerTheme {
        CourseDetailScreen(
            detail = FakeRepository().getCourseDetail("22CS501")!!,
            defaultThresholdPercent = 75,
            onBack = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewCourseDetailBelow() {
    AttendanceTrackerTheme {
        CourseDetailScreen(
            detail = FakeRepository().getCourseDetail("22CS503")!!,
            defaultThresholdPercent = 75,
            onBack = {},
        )
    }
}
