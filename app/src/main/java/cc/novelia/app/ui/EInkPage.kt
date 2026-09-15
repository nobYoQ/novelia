@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package cc.novelia.app.ui

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.UnderlineSpan
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.Position
import cc.novelia.app.data.ReaderSettings
import cc.novelia.app.reader.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Stable internal class EInkPageState(initial: Position?) {
    var pages by mutableStateOf<List<StaticPage>>(emptyList())
        private set
    var pageIndex by mutableIntStateOf(0)
        private set
    private var anchorParagraph = ((initial?.index ?: 1) - 1).coerceAtLeast(0)
    private var anchorOffset = initial?.textOffset ?: 0
    val current get() = pages.getOrNull(pageIndex)
    val paragraph get() = current?.lines?.firstOrNull()?.paragraph ?: anchorParagraph
    val textOffset get() = current?.lines?.firstOrNull()?.start ?: anchorOffset
    val canGoBack get() = pageIndex > 0
    val canGoForward get() = pageIndex + 1 < pages.size

    fun install(next: List<StaticPage>) {
        pages = next
        // Keep the requested character through every intermediate viewport measurement.
        // Snapping the anchor to each temporary page start progressively loses position.
        pageIndex = pageForAnchor(next, anchorParagraph, anchorOffset)
    }
    fun move(direction: Int) {
        if (pages.isNotEmpty()) {
            pageIndex = (pageIndex + direction).coerceIn(0, pages.lastIndex)
            anchorParagraph = paragraph
            anchorOffset = textOffset
        }
    }
    fun find(paragraph: Int) {
        anchorParagraph = paragraph
        anchorOffset = 0
        pageIndex = pageForAnchor(pages, paragraph, 0)
    }
}

internal data class MeasuredEInkChapter(val layouts: Map<Int, StaticLayout>, val pages: List<StaticPage>)

/** Apply secondary opacity to the current theme's paint without storing a fixed color. */
private class SecondaryOpacity(private val alpha: Float) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) { paint.alpha = (paint.alpha * alpha).toInt().coerceIn(0, 255) }
}

internal fun measureEInkChapter(paragraphs: List<ReadingParagraph>, settings: ReaderSettings, width: Int, height: Int, density: Float, fontScale: Float): MeasuredEInkChapter {
    val layouts = mutableMapOf<Int, StaticLayout>()
    val lines = mutableListOf<PageLine>()
    paragraphs.forEachIndexed { index, paragraph ->
        if (paragraph.imageUrl != null || paragraph.localImageId != null) {
            lines += PageLine(index, 0, 0, height, image = true)
        } else {
            val text = SpannableStringBuilder()
            paragraph.parts.forEachIndexed { partIndex, part ->
                if (partIndex > 0) text.append("\n\n")
                if (settings.parallel && part.source in listOf("sakura", "gpt", "youdao")) text.append(part.source.uppercase()).append("\n")
                val start = text.length
                if (settings.indent) text.append("　　")
                text.append(part.text)
                if (part.secondary) {
                    text.setSpan(SecondaryOpacity(settings.secondaryAlpha), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(AbsoluteSizeSpan(((settings.fontSize - 1) * density * fontScale).toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (settings.underline) text.setSpan(UnderlineSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (text.isEmpty()) text.append(" ")
            val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = settings.fontSize * density * fontScale
                color = android.graphics.Color.BLACK
                typeface = Typeface.create(Typeface.SANS_SERIF, if (settings.weight) Typeface.BOLD else Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0f, settings.lineHeight).build()
            layouts[index] = layout
            for (line in 0 until layout.lineCount) lines += PageLine(index, layout.getLineStart(line), layout.getLineEnd(line), layout.getLineBottom(line) - layout.getLineTop(line))
        }
    }
    return MeasuredEInkChapter(layouts, paginateLines(lines, height, (20 * density).toInt()))
}

/** Draw pre-measured full lines; swipes commit one page on release, with no scrolling frames. */
@Composable internal fun EInkPage(
    paragraphs: List<ReadingParagraph>, settings: ReaderSettings, state: EInkPageState,
    modifier: Modifier = Modifier, imageModel: (ReadingParagraph) -> Any?,
    onToggleMenu: () -> Unit, onSelect: (ReadingParagraph) -> Unit, onPage: (Int) -> Unit,
    background: Color = Color.White, foreground: Color = Color.Black,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    var expandedImage by remember { mutableStateOf<Any?>(null) }
    val latestPage by rememberUpdatedState(onPage)
    BoxWithConstraints(modifier.background(background).clipToBounds().pointerInput(settings.scrollPageTurn, settings.horizontalPageTurn) {
        var x = 0f; var y = 0f
        detectDragGestures(onDragStart = { x = 0f; y = 0f }, onDragCancel = { x = 0f; y = 0f }, onDragEnd = {
            val horizontal = abs(x) > abs(y)
            val enabled = if(horizontal) settings.horizontalPageTurn else settings.scrollPageTurn
            val delta = if(horizontal) x else y
            if (enabled && abs(delta) >= 40.dp.toPx()) latestPage(if (delta < 0) 1 else -1)
        }) { change, amount -> change.consume(); x += amount.x; y += amount.y }
    }) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        // Reflow only for typography/content/viewport changes. Turning a page reuses all layouts.
        val typography = listOf(settings.fontSize, settings.lineHeight, settings.weight, settings.indent, settings.parallel, settings.underline, settings.secondaryAlpha)
        val measured by produceState<MeasuredEInkChapter?>(null, paragraphs, typography, width, height, density) {
            value = null
            if (width != Constraints.Infinity && height != Constraints.Infinity) {
                val next = withContext(Dispatchers.Default) { measureEInkChapter(paragraphs, settings, width, height, density.density, density.fontScale) }
                state.install(next.pages)
                value = next
            }
        }
        val chapter = measured
        if (chapter == null) Text("正在分页…", Modifier.align(Alignment.Center), color = foreground)
        else {
            val page = state.current
            Column(Modifier.fillMaxSize().combinedClickable(onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu)) {
                page?.lines?.groupBy { it.paragraph }?.entries?.forEachIndexed { groupIndex, (index, lines) ->
                    if (groupIndex > 0) Spacer(Modifier.height(20.dp))
                    val paragraph = paragraphs[index]
                    if (lines.first().image) {
                        val model = imageModel(paragraph)
                        val request = remember(context, model) { ImageRequest.Builder(context).data(model).crossfade(false).build() }
                        var failed by remember(model) { mutableStateOf(false) }
                        Box(Modifier.fillMaxSize().combinedClickable(onClick = onToggleMenu, onLongClickLabel = "放大查看插图", onLongClick = { expandedImage = model }), contentAlignment = Alignment.Center) {
                            AsyncImage(request, "小说插图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
                            if (failed) Text("插图暂时无法加载", color = foreground)
                        }
                    } else {
                        val layout = chapter.layouts.getValue(index)
                        val top = layout.getLineTop(layout.getLineForOffset(lines.first().start))
                        val sliceHeight = lines.sumOf { it.height }
                        // Extremely large system fonts can make even one line taller than a
                        // landscape viewport. Fit that single line instead of clipping its glyphs.
                        val drawScale = (height.toFloat() / sliceHeight).coerceAtMost(1f)
                        val visibleText = layout.text.subSequence(lines.first().start, lines.last().end).toString()
                        Canvas(Modifier.fillMaxWidth().height(with(density) { (sliceHeight * drawScale).toDp() }).clipToBounds()
                            .semantics { text = AnnotatedString(visibleText) }
                            .combinedClickable(onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu,
                                onLongClickLabel = "选择段落、分享或添加笔记", onLongClick = { onSelect(paragraph) })) {
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                native.save()
                                native.scale(drawScale, drawScale)
                                native.translate(0f, -top.toFloat())
                                layout.paint.color = foreground.toArgb()
                                layout.draw(native)
                                native.restore()
                            }
                        }
                    }
                }
                if (page?.lines.isNullOrEmpty()) Text("本章暂无正文", color = foreground)
            }
        }
    }
    expandedImage?.let { model -> IllustrationViewer(model) { expandedImage = null } }
}
