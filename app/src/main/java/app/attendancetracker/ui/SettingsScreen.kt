package app.attendancetracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.attendancetracker.BuildConfig
import app.attendancetracker.security.SettingsStore
import app.attendancetracker.ui.theme.AppColors
import app.attendancetracker.ui.theme.AttendanceTrackerTheme

@Composable
fun SettingsScreen(
    thresholdPercent: Int,
    onSelectThreshold: (Int) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDebug: (() -> Unit)? = null,
) {
    var debugTapCount by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = AppColors.Background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.TextPrimary,
                )
            }

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
                        text = "Attendance Threshold",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text = "Used per subject to calculate classes you can miss or must attend.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SettingsStore.THRESHOLD_OPTIONS.forEach { option ->
                            val selected = thresholdPercent == option
                            Surface(
                                color = if (selected) AppColors.TextPrimary else AppColors.SurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) AppColors.TextPrimary else AppColors.BorderSubtle,
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectThreshold(option) },
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        text = "$option%",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selected) AppColors.Background else AppColors.TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                color = AppColors.Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AppColors.BorderSubtle),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Account & Local Data",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text = "Logging out clears persisted session cookies and deletes your encrypted credentials from this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onLogout,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.DangerRedSubtle,
                            contentColor = AppColors.DangerRed,
                        ),
                        border = BorderStroke(1.dp, AppColors.DangerRed.copy(alpha = 0.45f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                    ) {
                        Text("Log out")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "Attendance Tracker v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextMuted,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .then(
                        if (onOpenDebug != null) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                debugTapCount += 1
                                if (debugTapCount >= 5) {
                                    debugTapCount = 0
                                    onOpenDebug()
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewSettingsScreen() {
    AttendanceTrackerTheme {
        SettingsScreen(
            thresholdPercent = 75,
            onSelectThreshold = {},
            onLogout = {},
            onBack = {},
        )
    }
}
