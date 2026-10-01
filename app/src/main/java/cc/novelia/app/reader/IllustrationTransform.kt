package cc.novelia.app.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

internal data class IllustrationTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/** 按适配后位图边界限制变换，兼容横竖图产生的留白区域。 */
internal fun transformIllustration(
    current: IllustrationTransform,
    viewport: Size,
    image: Size,
    centroid: Offset = Offset(viewport.width / 2f, viewport.height / 2f),
    zoom: Float = 1f,
    pan: Offset = Offset.Zero,
): IllustrationTransform {
    if(viewport.isEmpty() || image.isEmpty()) return IllustrationTransform()
    val scale = (current.scale * zoom).coerceIn(1f, 5f)
    if(scale == 1f) return IllustrationTransform()
    val ratio = scale / current.scale
    val fromCenter = centroid - Offset(viewport.width / 2f, viewport.height / 2f)
    val offset = current.offset * ratio + fromCenter * (1f - ratio) + pan
    val fit = min(viewport.width / image.width, viewport.height / image.height)
    val maxX = max(0f, (image.width * fit * scale - viewport.width) / 2f)
    val maxY = max(0f, (image.height * fit * scale - viewport.height) / 2f)
    return IllustrationTransform(scale, Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY)))
}
