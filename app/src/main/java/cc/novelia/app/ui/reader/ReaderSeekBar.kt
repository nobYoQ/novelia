@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.reader.ChapterSeekIndex
import cc.novelia.app.reader.ParagraphScrollLayout
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.chapterSeekPage
import cc.novelia.app.reader.safeFraction
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable internal fun ReaderSeekBar(
    progress: Float, pageCount: Int?, enabled: Boolean, foreground: Color,
    onSeek: (Float) -> Unit, modifier: Modifier = Modifier,
) {
    val eInk = LocalEInkMode.current
    val reducedMotion = appReducedMotion()
    var draft by remember { mutableStateOf<Float?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val current = draft ?: progress.safeFraction()
    val latestSeek by rememberUpdatedState(onSeek)
    // A cancelled OS gesture must not leave an e-ink preview stuck on the control.
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if(it is DragInteraction.Cancel) draft = null }
    }
    LaunchedEffect(enabled) { if(!enabled) draft = null }
    val animatedRadius by animateDpAsState(if(dragging) 9.dp else 6.dp,
        tween(if(reducedMotion) 0 else AppMotion.Press), label = "reading seek thumb")
    val radius = if(reducedMotion) 6.dp else animatedRadius
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if(eInk && draft != null) "松手跳转" else "本章进度", style = MaterialTheme.typography.labelSmall, color = foreground)
            Text(if(pageCount != null) "${chapterSeekPage(current, pageCount) + 1} / ${pageCount.coerceAtLeast(1)} 页"
                else "${(current * 100).roundToInt()}%", Modifier.testTag("reader-seek-preview"), style = MaterialTheme.typography.labelSmall, color = foreground)
        }
        Slider(value = current, onValueChange = { next ->
            draft = next.safeFraction()
            if(!eInk) latestSeek(next.safeFraction())
        }, onValueChangeFinished = {
            draft?.let { latestSeek(it) }
            draft = null
        }, enabled = enabled, interactionSource = interaction,
            steps = pageCount?.let { (it - 2).coerceAtLeast(0) } ?: 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reader-seek-bar")
                .onPreviewKeyEvent { event ->
                    val direction = when(event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                    if(enabled && pageCount != null && pageCount > 1 && direction != 0) {
                        if(event.type == KeyEventType.KeyDown) latestSeek(
                            (chapterSeekPage(current, pageCount) + direction).coerceIn(0, pageCount - 1).toFloat() / (pageCount - 1))
                        true
                    } else false
                }
                .semantics { contentDescription = "本章阅读进度" },
            thumb = {
                Canvas(Modifier.size(width = 24.dp, height = 48.dp)) {
                    drawCircle(foreground.copy(alpha = if(enabled) 1f else .38f), radius.toPx())
                }
            }, track = { state ->
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val middle = size.height / 2
                    drawLine(foreground.copy(alpha = if(eInk) .5f else .2f), Offset(0f, middle), Offset(size.width, middle), size.height, StrokeCap.Round)
                    drawLine(foreground.copy(alpha = if(enabled) 1f else .38f), Offset(0f, middle), Offset(size.width * state.value, middle), size.height, StrokeCap.Round)
                }
            })
    }
}

/** Resolve an exact text line, then animate at most half a viewport even for a very distant seek. */
internal suspend fun LazyListState.seekChapter(
    progress: Float, index: ChapterSeekIndex, paragraphs: List<ReadingParagraph>,
    layouts: Map<Int, ParagraphScrollLayout>, animate: Boolean,
) {
    if(layoutInfo.totalItemsCount == 0) return
    val fraction = progress.safeFraction()
    val previousItem = firstVisibleItemIndex
    val previousOffset = firstVisibleItemScrollOffset
    val anchor = index.anchorAt(fraction)
    val item = when { fraction <= 0f -> 0; fraction >= 1f -> layoutInfo.totalItemsCount - 1; else -> anchor.paragraph + 1 }
    var offset = 0
    if(fraction > 0f && fraction < 1f) {
        val paragraph = paragraphs.getOrNull(anchor.paragraph)
        if(paragraph != null && paragraph.imageUrl == null && paragraph.localImageId == null) {
            if(layouts[paragraph.index] == null) scrollToItem(item)
            val layout = snapshotFlow { layouts[paragraph.index] }.first { it != null && it.parts.size == paragraph.parts.size }!!
            offset = layout.scrollOffsetAt(anchor.textOffset)
        }
    }
    if(!animate) { scrollToItem(item, offset); return }
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == item }
    val distance = visible?.let { (it.offset + offset).toFloat() }
    val limit = layoutInfo.viewportSize.height * .5f
    if(distance != null && abs(distance) <= limit) animateScrollBy(distance, tween(AppMotion.Page))
    else {
        val direction = if(item < previousItem || (item == previousItem && offset < previousOffset)) -1f else 1f
        scrollToItem(item, offset)
        // Scroll only the available approach distance; boundaries remain exactly 0% and 100%.
        var approach = 0f
        scroll { approach = scrollBy(-direction * limit.coerceAtMost(160f)) }
        animateScrollBy(-approach, tween(AppMotion.Page))
    }
}
