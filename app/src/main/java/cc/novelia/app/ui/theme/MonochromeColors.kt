package cc.novelia.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

private val ink = Color.Black
private val paper = Color.White
private val secondaryInk = Color(0xFF363636)
private val border = Color(0xFF707070)
private val selected = Color(0xFFD6D6D6)
private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable internal fun appImageColorFilter(): ColorFilter? =
    if (MaterialTheme.colorScheme == MonochromeColors) grayscale else null

internal val MonochromeColors = lightColorScheme(
    primary = ink, onPrimary = paper, primaryContainer = selected, onPrimaryContainer = ink,
    secondary = secondaryInk, onSecondary = paper, secondaryContainer = selected, onSecondaryContainer = ink,
    tertiary = secondaryInk, onTertiary = paper, tertiaryContainer = selected, onTertiaryContainer = ink,
    error = ink, onError = paper, errorContainer = selected, onErrorContainer = ink,
    background = paper, onBackground = ink, surface = paper, onSurface = ink,
    surfaceTint = paper, surfaceVariant = Color(0xFFE8E8E8), onSurfaceVariant = secondaryInk,
    surfaceContainerLowest = paper, surfaceContainerLow = Color(0xFFF7F7F7), surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFE8E8E8), surfaceContainerHighest = Color(0xFFDFDFDF),
    surfaceBright = paper, surfaceDim = selected, outline = border, outlineVariant = Color(0xFF939393),
    inverseSurface = ink, inverseOnSurface = paper, inversePrimary = paper, scrim = ink,
    primaryFixed = selected, primaryFixedDim = Color.LightGray, onPrimaryFixed = ink, onPrimaryFixedVariant = secondaryInk,
    secondaryFixed = selected, secondaryFixedDim = Color.LightGray, onSecondaryFixed = ink, onSecondaryFixedVariant = secondaryInk,
    tertiaryFixed = selected, tertiaryFixedDim = Color.LightGray, onTertiaryFixed = ink, onTertiaryFixedVariant = secondaryInk,
)
