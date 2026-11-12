package app.attendancetracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.attendancetracker.ui.theme.AppColors
import app.attendancetracker.ui.theme.AttendanceTrackerTheme

@Composable
fun EmptyStateView(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AppColors.Surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AppColors.BorderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "No courses yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = "Your subject-wise attendance, CIE marks, and lesson plans will appear here after your first sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.TextPrimary,
                        contentColor = AppColors.Background,
                    ),
                ) {
                    Text("Sync now")
                }
            }
        }
    }
}

@Composable
fun LoadingStateView(
    message: String = "Loading courses from portal...",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = AppColors.TextPrimary,
                trackColor = AppColors.SurfaceElevated,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ErrorStateView(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AppColors.Surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AppColors.DangerRed.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Could not load portal data",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onRetry,
                    border = BorderStroke(1.dp, AppColors.BorderSubtle),
                ) {
                    Text("Retry", color = AppColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
fun NeedsChallengeView(
    reason: String?,
    onOpenVerification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = AppColors.Surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AppColors.WarningAmber.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Verification required",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = reason ?: "The portal asked for a CAPTCHA or OTP check. Complete it directly to resume syncing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onOpenVerification,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.TextPrimary,
                        contentColor = AppColors.Background,
                    ),
                ) {
                    Text("Verify in portal")
                }
            }
        }
    }
}

@Composable
fun StatusBanner(
    title: String,
    message: String,
    isWarning: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isWarning) {
        AppColors.WarningAmber.copy(alpha = 0.45f)
    } else {
        AppColors.BorderSubtle
    }
    Surface(
        color = AppColors.SurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isWarning) AppColors.WarningAmber else AppColors.TextPrimary,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(
                    onClick = onAction,
                    border = BorderStroke(1.dp, AppColors.BorderSubtle),
                ) {
                    Text(actionLabel, color = AppColors.TextPrimary)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewEmptyState() {
    AttendanceTrackerTheme {
        EmptyStateView(onRefresh = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewLoadingState() {
    AttendanceTrackerTheme {
        LoadingStateView()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewErrorState() {
    AttendanceTrackerTheme {
        ErrorStateView(message = "Connection timed out while contacting parents.nie.ac.in.", onRetry = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewNeedsChallengeState() {
    AttendanceTrackerTheme {
        NeedsChallengeView(reason = "Portal requested CAPTCHA verification.", onOpenVerification = {})
    }
}
