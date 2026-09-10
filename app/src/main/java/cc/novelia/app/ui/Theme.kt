package cc.novelia.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006C4C), onPrimary = Color.White, primaryContainer = Color(0xFFADF2CF), onPrimaryContainer = Color(0xFF002115),
    secondary = Color(0xFF4C6357), onSecondary = Color.White, secondaryContainer = Color(0xFFCFE9D8), onSecondaryContainer = Color(0xFF102119),
    tertiary = Color(0xFF3C6373), tertiaryContainer = Color(0xFFC1E8FA), onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF7FAF5), onBackground = Color(0xFF191D1A), surface = Color(0xFFF7FAF5), onSurface = Color(0xFF191D1A),
    surfaceVariant = Color(0xFFDBE5DC), onSurfaceVariant = Color(0xFF404942), outline = Color(0xFF707A72), outlineVariant = Color(0xFFBFC9C0),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F5EF), surfaceContainer = Color(0xFFEBF0E9), surfaceContainerHigh = Color(0xFFE5EAE3), surfaceContainerHighest = Color(0xFFDFE5DE)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD6B4), onPrimary = Color(0xFF003825), primaryContainer = Color(0xFF005137), onPrimaryContainer = Color(0xFFADF2CF),
    secondary = Color(0xFFB3CDBC), secondaryContainer = Color(0xFF344C3E), onSecondaryContainer = Color(0xFFCFE9D8),
    tertiary = Color(0xFFA5CCDE), tertiaryContainer = Color(0xFF234B5A), onTertiaryContainer = Color(0xFFC1E8FA),
    background = Color(0xFF101512), onBackground = Color(0xFFDFE5DE), surface = Color(0xFF101512), onSurface = Color(0xFFDFE5DE),
    surfaceVariant = Color(0xFF404942), onSurfaceVariant = Color(0xFFBFC9C0), outline = Color(0xFF89938A), outlineVariant = Color(0xFF404942),
    surfaceContainerLowest = Color(0xFF0B100D), surfaceContainerLow = Color(0xFF191E1A), surfaceContainer = Color(0xFF1D221E), surfaceContainerHigh = Color(0xFF272C28), surfaceContainerHighest = Color(0xFF323732)
)
@Composable fun NoveliaTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val view = LocalView.current
    SideEffect { (view.context as? android.app.Activity)?.let { activity -> WindowCompat.getInsetsController(activity.window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    MaterialTheme(colorScheme = if(dark) DarkColors else LightColors, typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp), bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    ), content = content)
}
