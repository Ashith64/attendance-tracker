package app.attendancetracker.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.attendancetracker.security.PortalWebViewLoginController
import app.attendancetracker.security.StudentCredentials
import app.attendancetracker.ui.theme.AppColors

@Composable
fun ChallengeWebViewScreen(
    reason: String?,
    webViewController: PortalWebViewLoginController,
    credentials: StudentCredentials? = null,
    onCompleted: () -> Unit,
    onCancelToLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            webViewController.attachInteractiveChallengeClient(
                webView = this,
                credentials = credentials,
                onLoginSuccess = onCompleted,
                onNetworkError = { /* WebView displays its own error or user can retry */ },
            )
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background),
    ) {
        Surface(
            color = AppColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AppColors.WarningAmber.copy(alpha = 0.45f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Portal Verification Required",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.WarningAmber,
                )
                Text(
                    text = reason ?: "Complete the portal CAPTCHA, OTP, or login check below to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onCancelToLogin,
                        border = BorderStroke(1.dp, AppColors.BorderSubtle),
                    ) {
                        Text("Back to Login", color = AppColors.TextPrimary)
                    }
                }
            }
        }

        AndroidView(
            factory = { webView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
