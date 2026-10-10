package cc.novelia.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import cc.novelia.app.data.model.ReaderSettings

internal fun android.content.Context.activityOrNull(): android.app.Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return current as? android.app.Activity
}

// 浅灰绿纸色配低饱和强调色，容器层级也避免使用纯白。
private val LightColors = lightColorScheme(
    primary = Color(0xFF3E6B3B), onPrimary = Color(0xFFF6F8F1),
    primaryContainer = Color(0xFFDCE8D4), onPrimaryContainer = Color(0xFF304A2B),
    secondary = Color(0xFF596951), onSecondary = Color(0xFFF6F8F1),
    secondaryContainer = Color(0xFFE0E7D7), onSecondaryContainer = Color(0xFF35422F),
    tertiary = Color(0xFF68684C), onTertiary = Color(0xFFF6F8F1),
    tertiaryContainer = Color(0xFFE8E7D5), onTertiaryContainer = Color(0xFF44452E),
    background = Color(0xFFF1F4EC), onBackground = Color(0xFF30372E),
    surface = Color(0xFFF1F4EC), onSurface = Color(0xFF30372E),
    surfaceVariant = Color(0xFFDFE5D7), onSurfaceVariant = Color(0xFF596352),
    surfaceTint = Color(0xFF3E6B3B),
    inverseSurface = Color(0xFF30382D), inverseOnSurface = Color(0xFFEEF2E7), inversePrimary = Color(0xFFB6D2A7),
    outline = Color(0xFF727D6B), outlineVariant = Color(0xFFC6CDBC),
    surfaceBright = Color(0xFFF1F4EC), surfaceDim = Color(0xFFD8DFD0),
    surfaceContainerLowest = Color(0xFFF6F8F1), surfaceContainerLow = Color(0xFFEAEFE3),
    surfaceContainer = Color(0xFFE5EBDD), surfaceContainerHigh = Color(0xFFDFE5D7), surfaceContainerHighest = Color(0xFFD8DFD0)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD6B4), onPrimary = Color(0xFF003825), primaryContainer = Color(0xFF005137), onPrimaryContainer = Color(0xFFADF2CF),
    secondary = Color(0xFFB3CDBC), secondaryContainer = Color(0xFF344C3E), onSecondaryContainer = Color(0xFFCFE9D8),
    tertiary = Color(0xFFA5CCDE), tertiaryContainer = Color(0xFF234B5A), onTertiaryContainer = Color(0xFFC1E8FA),
    background = Color(0xFF101512), onBackground = Color(0xFFDFE5DE), surface = Color(0xFF101512), onSurface = Color(0xFFDFE5DE),
    surfaceVariant = Color(0xFF404942), onSurfaceVariant = Color(0xFFBFC9C0), outline = Color(0xFF89938A), outlineVariant = Color(0xFF404942),
    surfaceContainerLowest = Color(0xFF0B100D), surfaceContainerLow = Color(0xFF191E1A), surfaceContainer = Color(0xFF1D221E), surfaceContainerHigh = Color(0xFF272C28), surfaceContainerHighest = Color(0xFF323732)
)

/** 正文阅读配色独立于偏好设置和其他弹层使用的应用主题。 */
internal data class ReaderColors(val background: Color, val foreground: Color, val toolbar: Color)

// 跟随应用只决定深浅模式，与手动选择共用配色。
private val ReaderLightColors = ReaderColors(Color(0xFFE8F2E5), Color(0xFF263C2B), Color(0xFFCDDEC8))
private val ReaderDarkColors = ReaderColors(DarkColors.surface, DarkColors.onSurface,
    lerp(DarkColors.surface, Color.Black, .55f))

internal fun readerColors(settings: ReaderSettings, appColors: ColorScheme): ReaderColors =
    if(settings.resolvedTheme == "custom") settings.resolvedCustomColors.let {
        ReaderColors(Color(0xFF000000L or it.background), Color(0xFF000000L or it.text), Color(0xFF000000L or it.toolbar))
    } else readerColors(settings.resolvedTheme, appColors)

internal fun readerColors(theme: String, appColors: ColorScheme): ReaderColors = when(theme) {
    "paper" -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF282E27), Color(0xFFDED2B8))
    "light" -> ReaderLightColors
    "dark" -> ReaderDarkColors
    "monochrome" -> ReaderColors(Color.White, Color.Black, Color(0xFFE0E0E0))
    else -> if(appColors.surface.luminance() > .5f) ReaderLightColors else ReaderDarkColors
}

@Composable internal fun ReaderPageTheme(monochrome: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if(monochrome) lightColorScheme(primary = Color.Black, onPrimary = Color.White,
        surface = Color.White, onSurface = Color.Black, background = Color.White, onBackground = Color.Black,
        secondaryContainer = Color.White, onSecondaryContainer = Color.Black, outline = Color.Black) else MaterialTheme.colorScheme,
        content = content)
}

@Composable fun NoveliaTheme(theme: String, squareCorners: Boolean = false, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val view = LocalView.current
    val activity = LocalContext.current.activityOrNull()
    SideEffect { activity?.let { WindowCompat.getInsetsController(it.window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } } }
    CompositionLocalProvider(LocalSquareCorners provides squareCorners) {
    MaterialTheme(colorScheme = if(dark) DarkColors else LightColors, shapes = if(squareCorners) SquareShapes else DefaultShapes, typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp), bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    ), content = content)
    }
}
