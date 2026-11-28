package cc.novelia.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import cc.novelia.app.data.model.MONOCHROME_APP_THEME
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.model.SquareCornerTarget
import java.io.File

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

// 只让页面底色熄黑；卡片、菜单和弹层逐级抬亮，避免黑底吞掉操作区域的边界。
private val AmoledColors = DarkColors.copy(
    primary = Color(0xFF91C9AC), primaryContainer = Color(0xFF19372A), onPrimaryContainer = Color(0xFFBFE6CF),
    secondaryContainer = Color(0xFF202E26), onSecondaryContainer = Color(0xFFC4D4C9),
    background = Color.Black, surface = Color.Black, surfaceTint = Color.Black,
    onBackground = Color(0xFFD4DBD5), onSurface = Color(0xFFD4DBD5), onSurfaceVariant = Color(0xFFA7B0A9),
    surfaceDim = Color.Black, surfaceBright = Color(0xFF2C2C2C),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF101010), surfaceContainer = Color(0xFF161616),
    surfaceContainerHigh = Color(0xFF202020), surfaceContainerHighest = Color(0xFF2A2A2A),
    surfaceVariant = Color(0xFF272727), outline = Color(0xFF737D75), outlineVariant = Color(0xFF2B322D)
)

/** 表达实际生效的纯黑主题，浅色模式下即使偏好已开启也保持 false。 */
internal val LocalAmoledBlack = staticCompositionLocalOf { false }
internal val LocalAppDark = staticCompositionLocalOf { false }

/** 正文阅读配色独立于偏好设置和其他弹层使用的应用主题。 */
internal data class ReaderColors(val background: Color, val foreground: Color, val toolbar: Color)

// 跟随应用时使用对应深浅配色，并在 A 屏黑开启时跟随纯黑背景。
private val ReaderLightColors = ReaderColors(Color(0xFFE8F2E5), Color(0xFF263C2B), Color(0xFFCDDEC8))
private val ReaderDarkColors = ReaderColors(DarkColors.surface, DarkColors.onSurface,
    lerp(DarkColors.surface, Color.Black, .55f))
private val ReaderMonochromeColors = ReaderColors(Color.White, Color.Black, Color.White)

internal fun readerColors(settings: ReaderSettings, appColors: ColorScheme, customApp: Boolean = false): ReaderColors =
    if (customApp && settings.resolvedTheme == "system") {
        ReaderColors(appColors.background, appColors.onBackground,
            if (appColors.background == Color.Black) Color.Black else appColors.surfaceContainerHigh)
    } else if(settings.resolvedTheme == "custom") settings.resolvedCustomColors.let {
        ReaderColors(Color(0xFF000000L or it.background), Color(0xFF000000L or it.text), Color(0xFF000000L or it.toolbar))
    } else readerColors(settings.resolvedTheme, appColors)

internal fun nightReaderColors(settings: ReaderSettings): ReaderColors = readerColors(settings, DarkColors)

internal fun readerColors(theme: String, appColors: ColorScheme): ReaderColors = when(theme) {
    "paper" -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF282E27), Color(0xFFDED2B8))
    "light" -> ReaderLightColors
    "dark" -> ReaderDarkColors
    "monochrome" -> ReaderMonochromeColors
    else -> when {
        appColors == MonochromeColors -> ReaderMonochromeColors
        appColors.surface.luminance() > .5f -> ReaderLightColors
        appColors.surface == Color.Black -> ReaderDarkColors.copy(background = Color.Black, foreground = appColors.onSurface, toolbar = Color.Black)
        else -> ReaderDarkColors
    }
}

/** 夜间阅读的主题作用域包括正文、目录及所有后续弹层，不改变应用主题。 */
@Composable internal fun ReaderNightTheme(enabled: Boolean, eInk: Boolean = false, content: @Composable () -> Unit) {
    val colors = when {
        enabled -> DarkColors
        eInk && LocalThemePreset.current != null -> baseAppColors(LocalAppDark.current, LocalAmoledBlack.current)
        else -> MaterialTheme.colorScheme
    }
    CompositionLocalProvider(LocalAmoledBlack provides (LocalAmoledBlack.current && !enabled),
        LocalThemePreset provides LocalThemePreset.current.takeUnless { enabled || eInk },
        LocalThemeWallpaper provides LocalThemeWallpaper.current.takeUnless { enabled || eInk }) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

@Composable internal fun ReaderPageTheme(monochrome: Boolean, eInk: Boolean = LocalEInkMode.current, content: @Composable () -> Unit) {
    val colors = if(monochrome) MonochromeColors else MaterialTheme.colorScheme
    MaterialTheme(colorScheme = colors) {
        AppInteractionMode(eInk, LocalReducedMotion.current, content)
    }
}

internal fun baseAppColors(dark: Boolean, amoled: Boolean) = when {
    !dark -> LightColors
    amoled -> AmoledColors
    else -> DarkColors
}

@Composable fun NoveliaTheme(theme: String, squareCorners: Boolean = false, amoledBlack: Boolean = false,
    preset: ThemePreset? = null, imageFile: File? = null, squareCornerTargets: Set<SquareCornerTarget> = SquareCornerTarget.defaults,
    eInk: Boolean = LocalEInkMode.current, reducedMotion: Boolean = LocalReducedMotion.current,
    content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val monochrome = theme == MONOCHROME_APP_THEME
    val custom = if (monochrome || eInk) null else preset
    val black = dark && amoledBlack
    val colors = remember(monochrome, custom, dark, black) {
        when {
            monochrome -> MonochromeColors
            custom != null -> customColorScheme(custom, dark, black)
            else -> baseAppColors(dark, black)
        }
    }
    val wallpaper = rememberThemeWallpaper(custom.takeUnless { black }, imageFile)
    val lightBars = colors.onSurface.luminance() < colors.surface.luminance()
    val view = LocalView.current
    val activity = LocalContext.current.activityOrNull()
    SideEffect { activity?.let { WindowCompat.getInsetsController(it.window, view).apply { isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars } } }
    val activeCorners = if(squareCorners) squareCornerTargets else emptySet()
    CompositionLocalProvider(LocalSquareCornerTargets provides activeCorners, LocalAmoledBlack provides black,
        LocalAppDark provides dark, LocalThemePreset provides custom, LocalThemeWallpaper provides wallpaper) {
        MaterialTheme(colorScheme = colors, shapes = if(SquareCornerTarget.Cards in activeCorners) SquareShapes else DefaultShapes, typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp), bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
        )) {
            AppInteractionMode(eInk, reducedMotion, content)
        }
    }
}
