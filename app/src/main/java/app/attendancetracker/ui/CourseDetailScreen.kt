package app.attendancetracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.parser.CieData
import app.attendancetracker.parser.LessonPlanData
import app.attendancetracker.security.SettingsStore
import app.attendancetracker.ui.theme.AppColors
import app.attendancetracker.ui.theme.AppMotion
import app.attendancetracker.ui.theme.AttendanceTrackerTheme

enum class CourseDetailTab(val label: String) {
    ATTENDANCE("Attendance"),
    CIE("CIE"),
    LESSON_PLAN("Lesson Plan"),
}

@Composable
fun CourseDetailScreen(
    detail: CourseDetailUiModel,
    defaultThresholdPercent: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableStateOf(CourseDetailTab.ATTENDANCE) }
    var calcThreshold by rememberSaveable(defaultThresholdPercent) {
        mutableIntStateOf(defaultThresholdPercent)
    }
    var bunkMode by rememberSaveable { mutableStateOf(BunkMode.MISS) }
    var bunkCount by rememberSaveable { mutableIntStateOf(1) }

    val card = detail.card

    Scaffold(
        containerColor = AppColors.Background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item(key = "header") {
                CourseDetailHeader(
                    courseCode = card.courseCode,
                    courseName = card.courseName,
                    onBack = onBack,
                )
            }

            item(key = "summary") {
                CourseSummaryCard(card = card)
            }

            item(key = "bunk-calculator") {
                BunkCalculatorCard(
                    attended = card.attended ?: 0,
                    conducted = card.conducted ?: 0,
                    hasData = card.health != CourseHealth.NO_DATA,
                    thresholdPercent = calcThreshold,
                    mode = bunkMode,
                    count = bunkCount,
                    onSelectThreshold = { calcThreshold = it },
                    onSelectMode = { bunkMode = it },
                    onChangeCount = { bunkCount = it.coerceIn(0, 60) },
                )
            }

            item(key = "tabs") {
                CourseDetailTabRow(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it },
                )
            }

            item(key = "tab-content") {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(tween(AppMotion.DURATION_MEDIUM_MS)) togetherWith
                            fadeOut(tween(AppMotion.DURATION_FAST_MS))
                    },
                    label = "course-detail-tab",
                ) { tab ->
                    when (tab) {
                        CourseDetailTab.ATTENDANCE -> AttendanceHistorySection(
                            history = detail.attendanceHistory,
                        )
                        CourseDetailTab.CIE -> CieSection(
                            cieData = detail.cieData,
                        )
                        CourseDetailTab.LESSON_PLAN -> LessonPlanSection(
                            lessonPlan = detail.lessonPlan,
                        )
                    }
                }
            }

            item(key = "bottom-space") {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun CourseDetailHeader(
    courseCode: String,
    courseName: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = AppColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = courseCode,
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextSecondary,
            )
            Text(
                text = courseName,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CourseSummaryCard(
    card: CourseCardUiModel,
) {
    val accentColor = when (card.health) {
        CourseHealth.SAFE -> AppColors.SafeGreen
        CourseHealth.BELOW -> AppColors.DangerRed
        CourseHealth.NO_DATA -> AppColors.TextMuted
    }

    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = card.percentageText,
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = accentColor,
                )
                Text(
                    text = card.statusLineText,
                    style = MaterialTheme.typography.labelLarge,
                    color = accentColor,
                )
            }

            if (card.health != CourseHealth.NO_DATA &&
                card.attended != null &&
                card.conducted != null &&
                card.missed != null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricBox(
                        label = "Attended",
                        value = card.attended.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    MetricBox(
                        label = "Conducted",
                        value = card.conducted.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    MetricBox(
                        label = "Missed",
                        value = card.missed.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    val marginValue = when {
                        card.engineResult == null -> "-"
                        card.health == CourseHealth.SAFE -> card.engineResult.canMiss.toString()
                        else -> card.engineResult.mustAttendToRecover.toString()
                    }
                    val marginLabel = if (card.health == CourseHealth.SAFE) "Can miss" else "Need"
                    MetricBox(
                        label = marginLabel,
                        value = marginValue,
                        valueColor = accentColor,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Text(
                    text = "No classes conducted yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = card.updatedAgoText,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextMuted,
                )
                if (card.isStale && !card.latestErrorMessage.isNullOrBlank()) {
                    Text(
                        text = card.latestErrorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.WarningAmber,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = AppColors.TextPrimary,
) {
    Surface(
        color = AppColors.SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextMuted,
            )
        }
    }
}

@Composable
fun BunkCalculatorCard(
    attended: Int,
    conducted: Int,
    hasData: Boolean,
    thresholdPercent: Int,
    mode: BunkMode,
    count: Int,
    onSelectThreshold: (Int) -> Unit,
    onSelectMode: (BunkMode) -> Unit,
    onChangeCount: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sim = UiStateMapper.simulateBunk(
        attended = attended,
        conducted = conducted,
        thresholdPercent = thresholdPercent,
        mode = mode,
        count = count,
    )
    val resultColor = when (sim.projectedHealth) {
        CourseHealth.SAFE -> AppColors.SafeGreen
        CourseHealth.BELOW -> AppColors.DangerRed
        CourseHealth.NO_DATA -> AppColors.TextMuted
    }

    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Bunk Calculator",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
            )

            // Threshold selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Target threshold",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsStore.THRESHOLD_OPTIONS.forEach { option ->
                        val selected = option == thresholdPercent
                        Surface(
                            color = if (selected) AppColors.TextPrimary else AppColors.SurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                1.dp,
                                if (selected) AppColors.TextPrimary else AppColors.BorderSubtle,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectThreshold(option) },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = "$option%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) AppColors.Background else AppColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }

            // Mode toggle + stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .background(AppColors.SurfaceElevated, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        BunkMode.MISS to "Miss",
                        BunkMode.ATTEND to "Attend",
                    ).forEach { (itemMode, label) ->
                        val selected = mode == itemMode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AppColors.TextPrimary else AppColors.SurfaceElevated)
                                .clickable { onSelectMode(itemMode) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) AppColors.Background else AppColors.TextSecondary,
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StepperButton(
                        label = "-",
                        enabled = count > 0,
                        onClick = { onChangeCount(count - 1) },
                    )
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                    )
                    StepperButton(
                        label = "+",
                        enabled = count < 60,
                        onClick = { onChangeCount(count + 1) },
                    )
                }
            }

            // Live result
            Surface(
                color = AppColors.SurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = if (hasData || count > 0) {
                                "${sim.projectedAttended} / ${sim.projectedConducted} classes"
                            } else {
                                "No classes conducted"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextPrimary,
                        )
                        Text(
                            text = sim.projectedStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = resultColor,
                        )
                    }
                    Text(
                        text = sim.projectedPercentageText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = resultColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = AppColors.SurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) AppColors.TextPrimary else AppColors.TextMuted,
            )
        }
    }
}

@Composable
private fun CourseDetailTabRow(
    selectedTab: CourseDetailTab,
    onSelectTab: (CourseDetailTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CourseDetailTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) AppColors.SurfaceElevated else AppColors.Surface)
                    .clickable { onSelectTab(tab) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) AppColors.TextPrimary else AppColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun AttendanceHistorySection(
    history: List<AttendanceHistoryEntry>,
) {
    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Snapshot History",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
            )
            if (history.isEmpty()) {
                Text(
                    text = "No attendance snapshots recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextMuted,
                )
            } else {
                history.forEach { entry ->
                    Surface(
                        color = AppColors.SurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val countsText = if (entry.attended != null && entry.conducted != null) {
                                    "${entry.attended} / ${entry.conducted} classes"
                                } else {
                                    entry.errorMessage ?: "Fetch failed"
                                }
                                Text(
                                    text = countsText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary,
                                )
                                Text(
                                    text = entry.timestampLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextMuted,
                                )
                            }
                            Text(
                                text = entry.percentageText,
                                style = MaterialTheme.typography.titleMedium,
                                color = when (entry.status) {
                                    SnapshotStatus.OK -> AppColors.TextPrimary
                                    SnapshotStatus.SUSPECT -> AppColors.WarningAmber
                                    SnapshotStatus.FAILED -> AppColors.DangerRed
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CieSection(
    cieData: CieData?,
) {
    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CIE Marks",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                )
                if (cieData?.currentCie != null) {
                    Text(
                        text = "Course CIE: ${formatScore(cieData.currentCie)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.TextSecondary,
                    )
                }
            }

            if (cieData == null || cieData.assessments.isEmpty()) {
                Text(
                    text = "No CIE marks published for this course yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextMuted,
                )
            } else {
                if (!cieData.discrepancyNote.isNullOrBlank()) {
                    StatusBanner(
                        title = "Portal discrepancy",
                        message = cieData.discrepancyNote,
                        isWarning = true,
                    )
                }
                cieData.assessments.forEach { assessment ->
                    Surface(
                        color = AppColors.SurfaceElevated,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = assessment.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary,
                                )
                                Text(
                                    text = "Class avg ${formatScore(assessment.classAverage)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextMuted,
                                )
                            }
                            Text(
                                text = "${formatScore(assessment.obtainedMarks)} / ${formatScore(assessment.maxMarks)}",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonPlanSection(
    lessonPlan: LessonPlanData?,
) {
    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Lesson Plan",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
            )
            if (lessonPlan == null) {
                Text(
                    text = "Lesson plan not available for this course.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextMuted,
                )
            } else {
                if (lessonPlan.statusText.isNotBlank()) {
                    Text(
                        text = lessonPlan.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                }
                if (lessonPlan.entries.isEmpty()) {
                    Text(
                        text = "No topics listed yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextMuted,
                    )
                } else {
                    lessonPlan.entries.forEachIndexed { index, entry ->
                        Surface(
                            color = AppColors.SurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AppColors.TextMuted,
                                )
                                Text(
                                    text = entry.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatScore(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else "%.1f".format(value)
}
