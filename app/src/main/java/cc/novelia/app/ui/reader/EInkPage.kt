@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.AppTextButton

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.CharacterStyle
import android.text.style.UnderlineSpan
import android.text.style.UpdateAppearance
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.IllustrationViewer
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 静态分页的位置状态，适用于普通屏幕和电子纸。页码是测量结果，持久定位使用段落及字符锚点。
 * install 在字体/视口变化后重新找页，move 才把锚点推进到用户实际翻到的页首，
 * 避免连续多次中间测量把位置逐步向前“吸附”。ready 为 false 时禁止按旧页表翻页。
 */
@Stable internal class EInkPageState(initial: Position?) {
    // 渲染器将此列表实例绑定到已测量文本快照；即使新测量的行值相等，
    // 也须替换旧列表实例，避免关联到旧快照。
    var pages by mutableStateOf<List<StaticPage>>(emptyList(), referentialEqualityPolicy())
        private set
    var pageIndex by mutableIntStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set
    private var anchorParagraph = ((initial?.index ?: 1) - 1).coerceAtLeast(0)
    private var anchorOffset = initial?.textOffset ?: 0
    private var sourceParagraph: Int? = null
    private var content = emptyList<ReadingParagraph>()
    val current get() = pages.getOrNull(pageIndex)
    val paragraph get() = current?.lines?.firstOrNull()?.paragraph ?: anchorParagraph
    val textOffset get() = current?.lines?.firstOrNull()?.start ?: anchorOffset
    val sourceIndex get() = content.getOrNull(paragraph)?.index ?: sourceParagraph
    val canGoBack get() = pageIndex > 0
    val canGoForward get() = pageIndex + 1 < pages.size

    fun install(next: List<StaticPage>, paragraphs: List<ReadingParagraph> = content) {
        val previousParagraph = content.getOrNull(anchorParagraph)
        sourceParagraph?.let { source ->
            anchorParagraph = paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 }
                ?: paragraphs.lastIndex.coerceAtLeast(0)
        }
        // 语言顺序变化后，拼接文本中的字符偏移含义也会变化；
        // 此时恢复整个原始段落，避免译文与原文分离。
        if(previousParagraph != null && previousParagraph.parts != paragraphs.getOrNull(anchorParagraph)?.parts) anchorOffset = 0
        content = paragraphs
        sourceParagraph = paragraphs.getOrNull(anchorParagraph)?.index
        pages = next
        // 视口连续测量期间始终保留请求的字符位置；
        // 若每次对齐到临时页首，会逐步丢失原来的阅读位置。
        pageIndex = pageForAnchor(next, anchorParagraph, anchorOffset)
        ready = true
    }
    fun invalidate() { ready = false }
    fun move(direction: Int) {
        if (ready && pages.isNotEmpty()) {
            pageIndex = (pageIndex + direction).coerceIn(0, pages.lastIndex)
            anchorParagraph = paragraph
            anchorOffset = textOffset
            sourceParagraph = content.getOrNull(anchorParagraph)?.index
        }
    }
    fun find(paragraph: Int, offset: Int = 0, source: Int? = null) {
        anchorParagraph = paragraph
        anchorOffset = offset.coerceAtLeast(0)
        sourceParagraph = source ?: content.getOrNull(paragraph)?.index
        pageIndex = pageForAnchor(pages, paragraph, anchorOffset)
    }
}

internal data class MeasuredEInkChapter(val paragraphs: List<ReadingParagraph>, val layouts: Map<Int, StaticLayout>, val pages: List<StaticPage>)
private data class EInkMeasureInput(val paragraphs: List<ReadingParagraph>, val typography: List<Any>, val width: Int, val height: Int, val density: Float, val fontScale: Float)
private data class EInkMeasurement(val input: EInkMeasureInput, val chapter: MeasuredEInkChapter)

/** 在当前主题画笔上应用辅文本透明度，不保存固定颜色。 */
private class SecondaryOpacity(private val alpha: Float) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) { paint.alpha = (paint.alpha * alpha).toInt().coerceIn(0, 255) }
}

/**
 * 用 Android StaticLayout 一次测量整章，再把不可分的行交给纯分页算法。
 * width/height 为像素，字号需乘 density 与 fontScale，段距只乘 density。
 * 拼接标签、缩进和语言分隔符的顺序必须与 paragraphPartStarts 一致，才能复用搜索字符锚点。
 * 返回的段落、布局与页表属于同一测量快照，显示时不能与新一轮内容混用。
 */
internal fun measureEInkChapter(paragraphs: List<ReadingParagraph>, settings: ReaderSettings, width: Int, height: Int, density: Float, fontScale: Float, checkCancelled: () -> Unit = {}): MeasuredEInkChapter {
    val layouts = mutableMapOf<Int, StaticLayout>()
    val lines = mutableListOf<PageLine>()
    paragraphs.forEachIndexed { index, paragraph ->
        checkCancelled()
        if (paragraph.imageUrl != null || paragraph.localImageId != null) {
            lines += PageLine(index, 0, 0, height, image = true)
        } else {
            val text = SpannableStringBuilder()
            paragraph.parts.forEachIndexed { partIndex, part ->
                checkCancelled()
                if (partIndex > 0) text.append(READING_PART_SEPARATOR)
                if (settings.parallel && part.source in listOf("sakura", "gpt", "youdao")) text.append(part.source.uppercase()).append("\n")
                val start = text.length
                if (settings.indent) text.append("　　")
                text.append(part.text)
                if (part.secondary) {
                    text.setSpan(SecondaryOpacity(settings.secondaryAlpha), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(AbsoluteSizeSpan((readerPartFontSize(settings, true) * density * fontScale).toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            for (line in 0 until layout.lineCount) {
                if (line % 32 == 0) checkCancelled()
                lines += PageLine(index, layout.getLineStart(line), layout.getLineEnd(line), layout.getLineBottom(line) - layout.getLineTop(line))
            }
        }
    }
    val pairedParagraphs = paragraphs.indices.filterTo(mutableSetOf()) { paragraphs[it].parts.size > 1 }
    return MeasuredEInkChapter(paragraphs, layouts, paginateLines(lines, height, (settings.resolvedParagraphSpacing * density).roundToInt(), pairedParagraphs, checkCancelled))
}

/**
 * 绘制预先测量的完整行，翻页复用布局，手势在释放时最多提交一次翻页。
 * 字体、内容或视口变化才重新测量；电子纸与减少动效模式直接切页，普通模式可使用短过渡。
 * Canvas 只裁切当前页片段，同时提供可访问性文本、搜索高亮与段落长按入口。
 */
@Composable internal fun EInkPage(
    paragraphs: List<ReadingParagraph>, settings: ReaderSettings, state: EInkPageState,
    modifier: Modifier = Modifier, imageModel: (ReadingParagraph) -> Any?,
    onToggleMenu: () -> Unit, onSelect: (ReadingParagraph) -> Unit, onPage: (Int) -> Unit,
    background: Color = Color.White, foreground: Color = Color.Black,
    activeMatch: ReadingTextMatch? = null,
    interactionEnabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    var expandedImage by remember { mutableStateOf<Any?>(null) }
    val animate = !settings.eInkMode && !LocalEInkMode.current && !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()
    val pageShift = remember { Animatable(0f) }
    var lastPage by remember { mutableStateOf<Pair<List<StaticPage>, Int>?>(null) }
    LaunchedEffect(state.pages, state.pageIndex, animate) {
        val previous = lastPage
        lastPage = state.pages to state.pageIndex
        if(animate && previous != null && previous.first === state.pages && previous.second != state.pageIndex) {
            pageShift.snapTo(if(state.pageIndex > previous.second) 1f else -1f)
            pageShift.animateTo(0f, tween(AppMotion.Page))
        } else pageShift.snapTo(0f)
    }
    val latestPage by rememberUpdatedState(onPage)
    val tapNavigation = rememberReaderTapNavigation(settings.tapPageTurn, interactionEnabled && expandedImage == null, onToggleMenu, onPage)
    BoxWithConstraints(modifier.background(background).clipToBounds()
        .readerTapFeedback(tapNavigation, foreground, settings.eInkMode || LocalEInkMode.current)
        .readerTapNavigation(tapNavigation).pointerInput(settings.scrollPageTurn, settings.horizontalPageTurn) {
        var x = 0f; var y = 0f
        detectDragGestures(onDragStart = { x = 0f; y = 0f }, onDragCancel = { x = 0f; y = 0f }, onDragEnd = {
            val horizontal = abs(x) > abs(y)
            val enabled = if(horizontal) settings.horizontalPageTurn else settings.scrollPageTurn
            val delta = if(horizontal) x else y
            if (enabled && tapNavigation.canSwipe && abs(delta) >= 40.dp.toPx()) latestPage(if (delta < 0) 1 else -1)
        }) { change, amount -> change.consume(); x += amount.x; y += amount.y }
    }.padding(contentPadding).clipToBounds()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        // 仅在文字样式、正文或视口变化时重新排版，翻页复用全部测量布局。
        val typography = listOf(settings.fontSize, settings.lineHeight, settings.paragraphSpacing, settings.weight, settings.indent, settings.parallel, settings.underline, settings.secondaryAlpha)
        val input = EInkMeasureInput(paragraphs, typography, width, height, density.density, density.fontScale)
        val measured by produceState<EInkMeasurement?>(null, input) {
            // 测量前合并连续的视口变化和重复偏好更新。
            if(value != null) delay(120)
            if (width != Constraints.Infinity && height != Constraints.Infinity) {
                val next = withContext(Dispatchers.Default) {
                    val jobContext = currentCoroutineContext()
                    measureEInkChapter(paragraphs, settings, width, height, density.density, density.fontScale) { jobContext.ensureActive() }
                }
                state.install(next.pages, next.paragraphs)
                value = EInkMeasurement(input, next)
            }
        }
        // produceState 在新副作用启动前会保留旧值；不得把旧测量结果
        // 或旧状态中的页面与本轮组合的新正文配对。
        val chapter = measured?.takeIf { it.input == input && state.pages === it.chapter.pages }?.chapter
        SideEffect { if(chapter == null) state.invalidate() }
        if (chapter == null) Text("正在分页…", Modifier.align(Alignment.Center), color = foreground)
        else {
            val page = state.current
            Column(Modifier.fillMaxSize().graphicsLayer {
                // 再次翻页会取消上一轮副作用，并立即显示最新目标页。
                translationX = if(animate) pageShift.value * 12.dp.toPx() else 0f
                alpha = if(animate) 1f - abs(pageShift.value) * .12f else 1f
            }.combinedClickable(onClickLabel = "显示或收起阅读工具栏", onClick = tapNavigation::click)) {
                page?.lines?.groupBy { it.paragraph }?.entries?.forEachIndexed { groupIndex, (index, lines) ->
                    if (groupIndex > 0) Spacer(Modifier.height(settings.resolvedParagraphSpacing.dp))
                    val paragraph = chapter.paragraphs[index]
                    if (lines.first().image) {
                        val model = imageModel(paragraph)
                        var retry by remember(model) { mutableIntStateOf(0) }
                        val request = remember(context, model, retry) { ImageRequest.Builder(context).data(model).setParameter("readerRetry", retry).crossfade(false).build() }
                        var failed by remember(model) { mutableStateOf(false) }
                        Box(Modifier.fillMaxSize().combinedClickable(onClick = tapNavigation::click, onLongClickLabel = "放大查看插图", onLongClick = { expandedImage = model }), contentAlignment = Alignment.Center) {
                            AsyncImage(request, "小说插图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                onLoading = { failed = false }, onSuccess = { failed = false }, onError = { failed = true })
                            if(failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("插图暂时无法加载", color = foreground)
                                AppTextButton(onClick = { retry++ }) { Text("重新加载插图", color = foreground) }
                            }
                        }
                    } else {
                        val layout = chapter.layouts.getValue(index)
                        val top = layout.getLineTop(layout.getLineForOffset(lines.first().start))
                        val sliceHeight = lines.sumOf { it.height }
                        // 超大系统字号可能使一行文字高于整个横屏视口；
                        // 此时缩放适配该行，避免裁切字形。
                        val drawScale = (height.toFloat() / sliceHeight).coerceAtMost(1f)
                        val visibleText = layout.text.subSequence(lines.first().start, lines.last().end).toString()
                        Canvas(Modifier.fillMaxWidth().height(with(density) { (sliceHeight * drawScale).toDp() }).clipToBounds()
                            .semantics { text = AnnotatedString(visibleText) }
                            .combinedClickable(onClickLabel = "显示或收起阅读工具栏", onClick = tapNavigation::click,
                                onLongClickLabel = "选择段落、分享或添加笔记", onLongClick = { onSelect(paragraph) })) {
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                native.save()
                                native.scale(drawScale, drawScale)
                                native.translate(0f, -top.toFloat())
                                activeMatch?.takeIf { it.paragraph == index && it.end > it.start }?.let { match ->
                                    val start = match.textOffset(paragraph, settings).coerceIn(0, layout.text.length)
                                    val end = (start + match.end - match.start).coerceIn(start, layout.text.length)
                                    val path = android.graphics.Path()
                                    layout.getSelectionPath(start, end, path)
                                    native.drawPath(path, android.graphics.Paint().apply { color = foreground.copy(alpha = .2f).toArgb() })
                                }
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
