package cc.novelia.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val ink = Color.Black
private val paper = Color.White
// 保留一档灰色供长列表的局部标记和进度轨道使用。
private val highlight = Color(0xFFD6D6D6)
private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable internal fun isMonochromeTheme(): Boolean = MaterialTheme.colorScheme == MonochromeColors

@Composable internal fun appImageColorFilter(): ColorFilter? =
    if(isMonochromeTheme()) grayscale else null

@Composable internal fun monochromeBorder(): BorderStroke? =
    if(isMonochromeTheme()) BorderStroke(1.dp, ink) else null

/** 在现有栏位内绘制边线，保持测量尺寸及触控区域。 */
@Composable internal fun Modifier.monochromeDivider(atTop: Boolean = false, vertical: Boolean = false, color: Color = ink): Modifier {
    if(!isMonochromeTheme()) return this
    return drawWithContent {
        drawContent()
        val stroke = 1.dp.roundToPx().toFloat()
        if(vertical) {
            val x = if(layoutDirection == LayoutDirection.Ltr) size.width - stroke else 0f
            drawRect(color, Offset(x, 0f), Size(stroke, size.height))
        } else {
            drawRect(color, Offset(0f, if(atTop) 0f else size.height - stroke), Size(size.width, stroke))
        }
    }
}

internal val MonochromeColors = lightColorScheme(
    primary = ink, onPrimary = paper, primaryContainer = highlight, onPrimaryContainer = ink,
    secondary = ink, onSecondary = paper, secondaryContainer = highlight, onSecondaryContainer = ink,
    tertiary = ink, onTertiary = paper, tertiaryContainer = highlight, onTertiaryContainer = ink,
    error = ink, onError = paper, errorContainer = highlight, onErrorContainer = ink,
    background = paper, onBackground = ink, surface = paper, onSurface = ink,
    surfaceTint = paper, surfaceVariant = paper, onSurfaceVariant = ink,
    surfaceContainerLowest = paper, surfaceContainerLow = paper, surfaceContainer = paper,
    surfaceContainerHigh = paper, surfaceContainerHighest = paper,
    surfaceBright = paper, surfaceDim = paper, outline = ink, outlineVariant = ink,
    inverseSurface = ink, inverseOnSurface = paper, inversePrimary = paper, scrim = ink,
    primaryFixed = highlight, primaryFixedDim = highlight, onPrimaryFixed = ink, onPrimaryFixedVariant = ink,
    secondaryFixed = highlight, secondaryFixedDim = highlight, onSecondaryFixed = ink, onSecondaryFixedVariant = ink,
    tertiaryFixed = highlight, tertiaryFixedDim = highlight, onTertiaryFixed = ink, onTertiaryFixedVariant = ink,
)
