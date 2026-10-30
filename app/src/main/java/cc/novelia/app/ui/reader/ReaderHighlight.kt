package cc.novelia.app.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import cc.novelia.app.reader.ReadingTextMatch
import cc.novelia.app.ui.theme.AppMotion

internal data class ReaderHighlightLayer(val match: ReadingTextMatch, val alpha: Float)

/** 在同一正文上交接高亮；快速切句时从当前透明度继续，避免亮度闪烁。 */
@Composable internal fun rememberReaderHighlightLayers(
    match: ReadingTextMatch?, animate: Boolean,
): List<ReaderHighlightLayer> {
    val progress = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(emptyList<ReaderHighlightLayer>()) }
    var target by remember { mutableStateOf(match) }
    fun layers(): List<ReaderHighlightLayer> = buildList {
        previous.forEach { add(it.copy(alpha = it.alpha * (1f - progress.value))) }
        target?.let { add(ReaderHighlightLayer(it, progress.value)) }
    }.filter { it.alpha > .001f }
    LaunchedEffect(match, animate) {
        if(!animate) {
            previous = emptyList(); target = match; progress.snapTo(1f)
        } else if(target != match) {
            previous = layers()
            target = match
            progress.snapTo(0f)
            progress.animateTo(1f, tween(AppMotion.Standard))
            previous = emptyList()
        }
    }
    return if(animate) layers() else listOfNotNull(match?.let { ReaderHighlightLayer(it, 1f) })
}
