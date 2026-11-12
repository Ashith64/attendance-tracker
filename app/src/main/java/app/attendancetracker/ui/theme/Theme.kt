package app.attendancetracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object AppColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF101010)
    val SurfaceElevated = Color(0xFF171717)
    val BorderSubtle = Color(0xFF242424)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF9E9E9E)
    val TextMuted = Color(0xFF707070)
    val SafeGreen = Color(0xFF22C55E)
    val SafeGreenSubtle = Color(0xFF12261A)
    val DangerRed = Color(0xFFEF4444)
    val DangerRedSubtle = Color(0xFF2A1414)
    val WarningAmber = Color(0xFFEAB308)
    val WarningAmberSubtle = Color(0xFF262010)
    val AccentSubtle = Color(0xFFD4D4D4)
}

object AppMotion {
    const val DURATION_FAST_MS = 150
    const val DURATION_MEDIUM_MS = 220
}

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.TextPrimary,
    onPrimary = AppColors.Background,
    primaryContainer = AppColors.SurfaceElevated,
    onPrimaryContainer = AppColors.TextPrimary,
    secondary = AppColors.AccentSubtle,
    onSecondary = AppColors.Background,
    secondaryContainer = AppColors.SurfaceElevated,
    onSecondaryContainer = AppColors.TextPrimary,
    tertiary = AppColors.WarningAmber,
    onTertiary = AppColors.Background,
    tertiaryContainer = AppColors.WarningAmberSubtle,
    onTertiaryContainer = AppColors.TextPrimary,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceElevated,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.BorderSubtle,
    outlineVariant = AppColors.BorderSubtle,
    error = AppColors.DangerRed,
    onError = AppColors.TextPrimary,
    errorContainer = AppColors.DangerRedSubtle,
    onErrorContainer = AppColors.TextPrimary,
)

private val AppFontFamily = FontFamily.SansSerif

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun AttendanceTrackerTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content,
    )
}
