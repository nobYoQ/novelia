package cc.novelia.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import cc.novelia.app.data.model.ThemePreset
import kotlin.math.max
import kotlin.math.min

internal val LocalThemePreset = staticCompositionLocalOf<ThemePreset?> { null }

internal fun themeRgb(rgb: Long) = Color(0xFF000000L or rgb)

/** Material 的文字、图标和选中态共用色板。浅黄、近黑等主色也需要在卡片和底色上可辨。 */
internal fun customColorScheme(preset: ThemePreset, dark: Boolean, amoled: Boolean = false): ColorScheme {
    val base = baseAppColors(dark, amoled)
    val black = dark && amoled
    val background = if (black) Color.Black else themeRgb(if (dark) preset.darkBackground else preset.lightBackground)
    val ink = inkOn(background)
    val paper = if (ink == Color.White) Color.Black else Color.White
    val raised = lerp(background, ink, .14f).let {
        if (contrast(it, ink) >= 5f) it else lerp(background, paper, .30f)
    }
    fun surface(level: Float) = lerp(background, raised, level)
    val highest = if (black) base.surfaceContainerHighest else surface(1f)
    val seed = themeRgb(preset.accent)
    val primary = legible(seed, background, highest)
    val secondary = legible(lerp(seed, ink, .30f), background, highest)
    val error = legible(base.error, background, highest)
    val primaryContainer = lerp(background, primary, if (black) .20f else .25f)
    val secondaryContainer = lerp(background, primary, .17f)
    return base.copy(
        primary = primary, onPrimary = inkOn(primary), primaryContainer = primaryContainer,
        onPrimaryContainer = inkOn(primaryContainer), surfaceTint = if (black) Color.Black else primary,
        secondary = secondary, onSecondary = inkOn(secondary), secondaryContainer = secondaryContainer,
        onSecondaryContainer = inkOn(secondaryContainer),
        tertiary = secondary, onTertiary = inkOn(secondary), tertiaryContainer = secondaryContainer,
        onTertiaryContainer = inkOn(secondaryContainer),
        background = background, onBackground = ink, surface = background, onSurface = ink,
        surfaceVariant = highest, onSurfaceVariant = legible(lerp(background, ink, .75f), background, highest),
        surfaceContainerLowest = if (black) base.surfaceContainerLowest else surface(.10f),
        surfaceContainerLow = if (black) base.surfaceContainerLow else surface(.32f),
        surfaceContainer = if (black) base.surfaceContainer else surface(.52f),
        surfaceContainerHigh = if (black) base.surfaceContainerHigh else surface(.77f),
        surfaceContainerHighest = highest,
        surfaceDim = if (black) base.surfaceDim else lerp(background, Color.Black, .08f),
        surfaceBright = if (black) base.surfaceBright else lerp(background, Color.White, .08f),
        outline = lerp(background, ink, .58f), outlineVariant = lerp(background, ink, .25f),
        inverseSurface = ink, inverseOnSurface = paper, inversePrimary = legible(seed, ink, ink),
        error = error, onError = inkOn(error),
    )
}

private fun contrast(a: Color, b: Color): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (max(x, y) + .05f) / (min(x, y) + .05f)
}

internal fun inkOn(color: Color) = if (contrast(color, Color.Black) >= contrast(color, Color.White)) Color.Black else Color.White

private fun legible(color: Color, background: Color, card: Color): Color {
    fun readable(value: Color) = contrast(value, background) >= 4.5f && contrast(value, card) >= 4.5f
    if (readable(color)) return color
    val ink = inkOn(background)
    var low = 0f
    var high = 1f
    repeat(10) {
        val middle = (low + high) / 2
        if (readable(lerp(color, ink, middle))) high = middle else low = middle
    }
    return lerp(color, ink, high)
}

@Composable internal fun pageContainerColor(): Color =
    if (LocalThemeWallpaper.current != null) Color.Transparent else MaterialTheme.colorScheme.background

@Composable internal fun wallpaperCardColor(color: Color): Color =
    LocalThemeWallpaper.current?.let { color.copy(alpha = it.cardOpacity) } ?: color

@Composable internal fun CardColors.withWallpaperOpacity(): CardColors =
    copy(containerColor = wallpaperCardColor(containerColor))
