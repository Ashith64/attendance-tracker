package app.attendancetracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.attendancetracker.ui.theme.AppColors
import app.attendancetracker.ui.theme.AppMotion
import app.attendancetracker.ui.theme.AttendanceTrackerTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onCourseClick: (String) -> Unit,
    onOpenChallenge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        containerColor = AppColors.Background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            HomeHeader(
                studentName = state.studentName,
                termLabel = state.termLabel,
                lastSyncText = state.lastSyncText,
                isSyncing = state.isSyncing,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
            )

            AnimatedVisibility(
                visible = state.isSyncing,
                enter = fadeIn(animationSpec = tween(AppMotion.DURATION_FAST_MS)),
                exit = fadeOut(animationSpec = tween(AppMotion.DURATION_MEDIUM_MS)),
            ) {
                val progress = state.syncProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = AppColors.AccentSubtle,
                        trackColor = AppColors.SurfaceElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = AppColors.AccentSubtle,
                        trackColor = AppColors.SurfaceElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = state.isSyncing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (val screenState = state.screenState) {
                    DashboardScreenState.Loading -> {
                        LoadingStateView()
                    }

                    DashboardScreenState.Empty -> {
                        EmptyStateView(onRefresh = onRefresh)
                    }

                    is DashboardScreenState.Error -> {
                        if (state.courses.isEmpty()) {
                            ErrorStateView(
                                message = screenState.message,
                                onRetry = onRefresh,
                            )
                        } else {
                            // Show stale courses without technical error banner
                            CoursesListContent(
                                state = state,
                                errorBanner = null,
                                onCourseClick = onCourseClick,
                                onOpenChallenge = onOpenChallenge,
                            )
                        }
                    }

                    is DashboardScreenState.NeedsChallenge -> {
                        if (state.courses.isEmpty()) {
                            NeedsChallengeView(
                                reason = screenState.reason,
                                onOpenVerification = onOpenChallenge,
                            )
                        } else {
                            CoursesListContent(
                                state = state,
                                challengeReason = screenState.reason,
                                onCourseClick = onCourseClick,
                                onOpenChallenge = onOpenChallenge,
                            )
                        }
                    }

                    DashboardScreenState.Content -> {
                        if (state.courses.isEmpty()) {
                            EmptyStateView(onRefresh = onRefresh)
                        } else {
                            CoursesListContent(
                                state = state,
                                onCourseClick = onCourseClick,
                                onOpenChallenge = onOpenChallenge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    studentName: String?,
    termLabel: String?,
    lastSyncText: String,
    isSyncing: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Attendance Tracker",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.TextPrimary,
                )
                if (!studentName.isNullOrBlank()) {
                    Text(
                        text = studentName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !isSyncing,
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = if (isSyncing) AppColors.TextMuted else AppColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = termLabel ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextMuted,
            )
            Text(
                text = lastSyncText,
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextMuted,
            )
        }
    }
}

@Composable
private fun CoursesListContent(
    state: HomeUiState,
    errorBanner: String? = null,
    challengeReason: String? = null,
    onCourseClick: (String) -> Unit,
    onOpenChallenge: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.layoutChangedWarning) {
            item(key = "layout-warning") {
                StatusBanner(
                    title = "Portal layout may have changed",
                    message = "Recent syncs could not read the expected page structure. Showing your last saved data.",
                    isWarning = true,
                )
            }
        }

        if (challengeReason != null) {
            item(key = "challenge-banner") {
                StatusBanner(
                    title = "Verification required",
                    message = challengeReason,
                    isWarning = true,
                    actionLabel = "Verify",
                    onAction = onOpenChallenge,
                )
            }
        }

        items(
            items = state.courses,
            key = { "${it.term}:${it.courseCode}" },
        ) { course ->
            CourseAttendanceCard(
                course = course,
                thresholdPercent = state.thresholdPercent,
                onClick = { onCourseClick(course.courseCode) },
            )
        }

        item(key = "bottom-spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun AttendanceProgressBar(
    progress: Float?,
    thresholdPercent: Int,
    health: CourseHealth,
    modifier: Modifier = Modifier,
) {
    val barColor = when (health) {
        CourseHealth.SAFE -> AppColors.SafeGreen
        CourseHealth.BELOW -> AppColors.DangerRed
        CourseHealth.NO_DATA -> AppColors.TextMuted.copy(alpha = 0.3f)
    }
    val trackColor = AppColors.SurfaceElevated

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(trackColor, RoundedCornerShape(3.dp)),
    ) {
        if (progress != null && progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(barColor, RoundedCornerShape(3.dp)),
            )
        }
        if (thresholdPercent in 1..99) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(thresholdPercent / 100f),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(width = 2.dp, height = 6.dp)
                        .background(AppColors.TextMuted.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
fun CourseAttendanceCard(
    course: CourseCardUiModel,
    thresholdPercent: Int = 75,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = when (course.health) {
        CourseHealth.SAFE -> AppColors.SafeGreen
        CourseHealth.BELOW -> AppColors.DangerRed
        CourseHealth.NO_DATA -> AppColors.TextMuted
    }

    val progress = if (course.attended != null && course.conducted != null && course.conducted > 0) {
        (course.attended.toFloat() / course.conducted.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = course.courseCode,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = AppColors.TextMuted,
                    )
                    Text(
                        text = course.courseName,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                        ),
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = course.percentageText,
                    style = if (course.health == CourseHealth.NO_DATA) {
                        MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                        )
                    } else {
                        MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                        )
                    },
                    color = accentColor,
                )
            }

            AttendanceProgressBar(
                progress = progress,
                thresholdPercent = thresholdPercent,
                health = course.health,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (course.health != CourseHealth.NO_DATA &&
                    course.attended != null &&
                    course.conducted != null &&
                    course.missed != null
                ) {
                    Text(
                        text = "${course.attended}/${course.conducted} ${course.unit} · ${course.missed} missed",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = AppColors.TextSecondary,
                    )
                    Text(
                        text = course.guidanceText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp,
                        ),
                        color = accentColor,
                    )
                } else {
                    Text(
                        text = course.guidanceText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = AppColors.TextMuted,
                    )
                }
            }
        }
    }
}
