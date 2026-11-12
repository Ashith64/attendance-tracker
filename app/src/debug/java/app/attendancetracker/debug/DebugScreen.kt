package app.attendancetracker.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.attendancetracker.data.AttendanceRepository
import app.attendancetracker.data.CourseEntity
import app.attendancetracker.data.SnapshotCategory
import app.attendancetracker.data.SnapshotEntity
import app.attendancetracker.data.SnapshotStatus
import app.attendancetracker.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugScreen(
    repository: AttendanceRepository,
    useFakeData: Boolean,
    onToggleFakeData: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestTerm by repository.observeLatestTerm().collectAsState(initial = null)
    val courses by repository.observeCoursesInLatestTerm().collectAsState(initial = emptyList())
    val snapshots by repository.observeSnapshotsForLatestTerm().collectAsState(initial = emptyList())

    val latestByCourseAndCategory = remember(snapshots) {
        snapshots
            .groupBy { it.courseCode to it.category }
            .mapValues { (_, list) -> list.first() }
    }

    Scaffold(
        containerColor = AppColors.Background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column {
                    Text(
                        text = "Debug Inspector",
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text = "Debug build only · Credentials & cookies never shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextMuted,
                    )
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Surface(
                        color = AppColors.Surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AppColors.BorderSubtle),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Use FakeRepository (Debug Flag)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AppColors.TextPrimary,
                                )
                                Text(
                                    text = "Preview UI with in-memory sample courses instead of Room",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSecondary,
                                )
                            }
                            Switch(
                                checked = useFakeData,
                                onCheckedChange = onToggleFakeData,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AppColors.Background,
                                    checkedTrackColor = AppColors.TextPrimary,
                                ),
                            )
                        }
                    }
                }

                item {
                    Surface(
                        color = AppColors.Surface,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AppColors.BorderSubtle),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Latest Term",
                                style = MaterialTheme.typography.labelLarge,
                                color = AppColors.TextSecondary,
                            )
                            Text(
                                text = latestTerm?.let {
                                    "${it.label} (semId=${it.semId}, sem=${it.semesterNumber})"
                                } ?: "No term discovered yet",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = AppColors.TextPrimary,
                            )
                        }
                    }
                }

                if (courses.isEmpty()) {
                    item {
                        Surface(
                            color = AppColors.Surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, AppColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "No synced courses in database yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    items(courses, key = { "${it.term}:${it.code}" }) { course ->
                        DebugCourseCard(
                            course = course,
                            snapshotsByCategory = SnapshotCategory.entries.associateWith { cat ->
                                latestByCourseAndCategory[course.code to cat]
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugCourseCard(
    course: CourseEntity,
    snapshotsByCategory: Map<SnapshotCategory, SnapshotEntity?>,
) {
    Surface(
        color = AppColors.Surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AppColors.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = course.code,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = AppColors.TextSecondary,
                )
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.TextPrimary,
                )
            }

            SnapshotCategory.entries.forEachIndexed { index, category ->
                if (index > 0) {
                    HorizontalDivider(color = AppColors.BorderSubtle)
                }
                DebugCategorySection(
                    category = category,
                    snapshot = snapshotsByCategory[category],
                )
            }
        }
    }
}

@Composable
private fun DebugCategorySection(
    category: SnapshotCategory,
    snapshot: SnapshotEntity?,
) {
    val statusText = snapshot?.status?.name ?: "NOT_FETCHED"
    val statusColor = when (snapshot?.status) {
        SnapshotStatus.OK -> AppColors.SafeGreen
        SnapshotStatus.SUSPECT -> AppColors.WarningAmber
        SnapshotStatus.FAILED -> AppColors.DangerRed
        null -> AppColors.TextMuted
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = statusColor,
            )
        }

        DebugFieldRow(
            label = "fetchedAt",
            value = snapshot?.let { "${formatEpochMs(it.fetchedAt)} (${it.fetchedAt})" } ?: "Never",
        )
        DebugFieldRow(
            label = "sourceUrl",
            value = snapshot?.sourceUrl?.ifBlank { "-" } ?: "-",
        )
        if (!snapshot?.errorMessage.isNullOrBlank()) {
            DebugFieldRow(
                label = "error",
                value = snapshot?.errorMessage ?: "-",
            )
        }
        DebugFieldRow(
            label = "raw",
            value = snapshot?.payloadJson?.ifBlank { "-" } ?: "-",
        )
    }
}

@Composable
private fun DebugFieldRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = AppColors.TextSecondary,
        )
    }
}

private fun formatEpochMs(epochMs: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return formatter.format(Date(epochMs))
}
