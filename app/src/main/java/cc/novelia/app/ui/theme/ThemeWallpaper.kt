package cc.novelia.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.scale
import cc.novelia.app.data.model.ThemeBarGlass
import cc.novelia.app.data.model.ThemePreset
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size as CoilSize
import coil.transform.Transformation
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

internal class WallpaperViewport { var bounds by mutableStateOf(Rect.Zero) }
internal class ThemeWallpaper(val painter: Painter, val top: Painter?, val bottom: Painter?,
    val overlay: Float, val cardOpacity: Float, val viewport: WallpaperViewport)
internal val LocalThemeWallpaper = staticCompositionLocalOf<ThemeWallpaper?> { null }

@Composable internal fun rememberThemeWallpaper(preset: ThemePreset?, file: File?): ThemeWallpaper? {
    if (preset?.image == null || file == null) return null
    val body = wallpaperPainter(file, preset.image, preset.blur)
    fun strength(bar: ThemeBarGlass) = (preset.blur + (100 - preset.blur) * bar.blur / 100f).roundToInt()
    val topStrength = strength(preset.topBar)
    val bottomStrength = strength(preset.bottomBar)
    val top = when {
        !preset.topBar.enabled -> null
        topStrength == preset.blur -> body
        else -> wallpaperPainter(file, preset.image, topStrength)
    }
    val bottom = when {
        !preset.bottomBar.enabled -> null
        bottomStrength == preset.blur -> body
        bottomStrength == topStrength && top != null -> top
        else -> wallpaperPainter(file, preset.image, bottomStrength)
    }
    return ThemeWallpaper(body, top, bottom, preset.overlay / 100f, preset.cardOpacity / 100f, remember { WallpaperViewport() })
}

@Composable private fun wallpaperPainter(file: File, hash: String, blur: Int): Painter {
    val context = LocalContext.current
    val request = remember(context, file, hash, blur) {
        ImageRequest.Builder(context).data(file).scale(Scale.FIT)
            .size(if (blur == 0) 2048 else BLUR_EDGE)
            .memoryCacheKey("theme-wallpaper-v1:$hash:$blur")
            .apply { if (blur > 0) { allowHardware(false); transformations(WallpaperBlur(blur)) } }
            .crossfade(false).build()
    }
    return rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
}

internal fun Modifier.themePaper(color: Color, wallpaper: ThemeWallpaper?, anchorBars: Boolean = false): Modifier {
    if (wallpaper == null) return background(color)
    val anchor = if (anchorBars) onGloballyPositioned {
        wallpaper.viewport.bounds = Rect(it.positionInWindow(), it.size.toSize())
    } else this
    return anchor.drawBehind { drawPaper(wallpaper.painter, size, Offset.Zero, color, wallpaper.overlay) }
}

/** 正文和栏位从同一张居中裁切的壁纸取样，栏位移动或横竖屏切换时图案保持对齐。 */
private fun DrawScope.drawPaper(painter: Painter, viewport: Size, origin: Offset, color: Color, overlay: Float) {
    drawRect(color)
    if (viewport.isEmpty()) return
    val source = painter.intrinsicSize
    val scaled = if (source.isSpecified && !source.isEmpty()) {
        source * maxOf(viewport.width / source.width, viewport.height / source.height)
    } else viewport
    clipRect {
        translate(origin.x + (viewport.width - scaled.width) / 2, origin.y + (viewport.height - scaled.height) / 2) {
            with(painter) { draw(scaled) }
        }
    }
    if (overlay > 0f) drawRect(color.copy(alpha = overlay))
}

internal enum class ThemeBar { Top, Bottom }
internal data class BarBackground(val modifier: Modifier, val color: Color)

@Composable internal fun themeBarBackground(edge: ThemeBar, opaque: Color): BarBackground {
    val preset = LocalThemePreset.current
    val bar = if (edge == ThemeBar.Top) preset?.topBar else preset?.bottomBar
    if (bar?.enabled != true || LocalAmoledBlack.current || LocalEInkMode.current) return BarBackground(Modifier, opaque)
    val paper = MaterialTheme.colorScheme.background
    val wallpaper = LocalThemeWallpaper.current
    val painter = if (edge == ThemeBar.Top) wallpaper?.top else wallpaper?.bottom
    val opacity = bar.opacity / 100f
    if (painter == null || wallpaper == null) return BarBackground(Modifier, lerp(paper, opaque, opacity))
    var position by remember { mutableStateOf(Offset.Zero) }
    val glass = Modifier.onGloballyPositioned { position = it.positionInWindow() }.drawBehind {
        val frame = wallpaper.viewport.bounds
        if (!frame.isEmpty) drawPaper(painter, frame.size, frame.topLeft - position, paper, wallpaper.overlay)
        drawRect(opaque.copy(alpha = opacity))
    }
    return BarBackground(glass, Color.Transparent)
}

private const val BLUR_EDGE = 640

/** 在 Coil 的后台变换中生成并缓存静态模糊图，API 26 起使用相同路径。 */
private class WallpaperBlur(private val strength: Int) : Transformation {
    override val cacheKey = "novelia-wallpaper-blur-v1-$strength"

    override suspend fun transform(input: Bitmap, size: CoilSize): Bitmap {
        val scale = (BLUR_EDGE.toFloat() / maxOf(input.width, input.height)).coerceAtMost(1f)
        val width = (input.width * scale).roundToInt().coerceAtLeast(1)
        val height = (input.height * scale).roundToInt().coerceAtLeast(1)
        val sampled = input.scale(width, height, filter = true)
        val pixels = IntArray(width * height)
        sampled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (sampled !== input) sampled.recycle()
        val radius = (maxOf(width, height) * strength / 2600f).roundToInt().coerceAtLeast(1)
        val buffer = IntArray(pixels.size)
        val context = currentCoroutineContext()
        repeat(2) {
            boxBlurPass(pixels, buffer, width, height, radius) { context.ensureActive() }
            boxBlurPass(buffer, pixels, height, width, radius) { context.ensureActive() }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

/** 滑动窗口取平均并转置，下一遍即可沿另一轴处理。透明像素按 alpha 加权，边缘延拓。 */
internal fun boxBlurPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, check: () -> Unit) {
    val count = radius * 2 + 1
    for (y in 0 until height) {
        check()
        val row = y * width
        var a = 0; var r = 0; var g = 0; var b = 0
        fun add(pixel: Int, sign: Int) {
            val weight = (pixel ushr 24) * sign
            a += weight
            r += ((pixel ushr 16) and 255) * weight
            g += ((pixel ushr 8) and 255) * weight
            b += (pixel and 255) * weight
        }
        for (i in -radius..radius) add(src[row + i.coerceIn(0, width - 1)], 1)
        for (x in 0 until width) {
            dst[x * height + y] = if (a == 0) 0 else
                ((a / count) shl 24) or ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
            add(src[row + (x - radius).coerceAtLeast(0)], -1)
            add(src[row + (x + radius + 1).coerceAtMost(width - 1)], 1)
        }
    }
}
