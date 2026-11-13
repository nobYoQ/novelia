@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppTextButton
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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.media.IllustrationViewer
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import cc.novelia.app.ui.components.media.RecoveringAsyncImage as AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.mutableFloatStateOf

/**
 * 静态分页的位置状态，适用于普通屏幕和电子纸。页码是测量结果，持久定位使用段落及字符锚点。
 * install 在字体/视口变化后重新找页，move 才把锚点推进到用户实际翻到的页首，
 * 避免连续多次中间测量把位置逐步向前“吸附”。ready 为 false 时禁止按旧页表翻页。
 */
@Stable internal class EInkPageState(initial: Position?) : ReaderPager {
    // 渲染器将此列表实例绑定到已测量文本快照；即使新测量的行值相等，
    // 也须替换旧列表实例，避免关联到旧快照。
    override var pages by mutableStateOf<List<StaticPage>>(emptyList(), referentialEqualityPolicy())
        private set
    override var pageIndex by mutableIntStateOf(0)
        private set
    override var ready by mutableStateOf(false)
        private set
    override var visiblePageCount by mutableIntStateOf(1)
        private set
    private var anchorParagraph = ((initial?.index ?: 0) - 1).coerceAtLeast(CHAPTER_HEADING_PARAGRAPH)
    private var anchorOffset = initial?.textOffset ?: 0
    private var sourceParagraph: Int? = null
    private var content = emptyList<ReadingParagraph>()
    val current get() = pages.getOrNull(pageIndex)
    override val paragraph get() = current?.lines?.firstOrNull()?.paragraph ?: anchorParagraph
    override val textOffset get() = current?.lines?.firstOrNull()?.start ?: anchorOffset
    override val sourceIndex get() = content.getOrNull(paragraph)?.index ?: sourceParagraph
    /** 保存、切换模式和刷新使用请求的字符锚点；页首只用于当前页的显示与交互。 */
    override val anchor get() = ReadingRestoreAnchor(anchorParagraph, sourceParagraph, anchorOffset)
    override val canGoBack get() = pageIndex > 0
    override val canGoForward get() = pageIndex + visiblePageCount < pages.size

    fun install(next: List<StaticPage>, paragraphs: List<ReadingParagraph> = content, columns: Int = 1) {
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
        visiblePageCount = columns.coerceIn(1, 2)
        // 视口连续测量期间始终保留请求的字符位置；
        // 若每次对齐到临时页首，会逐步丢失原来的阅读位置。
        pageIndex = spreadStart(pageForAnchor(next, anchorParagraph, anchorOffset), next.size, visiblePageCount)
        // 章末入口、缺失的独立标题等没有对应段落时，才回退到实际页首。
        if(anchorParagraph !in paragraphs.indices &&
            !(anchorParagraph == CHAPTER_HEADING_PARAGRAPH && next.firstOrNull()?.lines?.firstOrNull()?.paragraph == CHAPTER_HEADING_PARAGRAPH)) {
            val first = current?.lines?.firstOrNull()
            anchorParagraph = first?.paragraph ?: CHAPTER_HEADING_PARAGRAPH
            anchorOffset = first?.start ?: 0
            sourceParagraph = paragraphs.getOrNull(anchorParagraph)?.index
        }
        ready = true
    }
    override fun invalidate() { ready = false }
    /** 极快的纯插图测量可能早于上一帧的失效副作用完成；只确认当前显示的同一份页表。 */
    fun confirm(pages: List<StaticPage>) { if (this.pages === pages) ready = true }
    override fun move(direction: Int) {
        if (ready && pages.isNotEmpty()) {
            pageIndex = spreadStart(pageIndex + direction * visiblePageCount, pages.size, visiblePageCount)
            anchorParagraph = paragraph
            anchorOffset = textOffset
            sourceParagraph = content.getOrNull(anchorParagraph)?.index
        }
    }
    override fun seekPage(index: Int) {
        if(!ready || pages.isEmpty()) return
        pageIndex = spreadStart(index, pages.size, visiblePageCount)
        anchorParagraph = paragraph
        anchorOffset = textOffset
        sourceParagraph = content.getOrNull(anchorParagraph)?.index
    }
    override fun find(paragraph: Int, offset: Int, source: Int?) {
        anchorParagraph = paragraph
        anchorOffset = offset.coerceAtLeast(0)
        sourceParagraph = source ?: content.getOrNull(paragraph)?.index
        pageIndex = spreadStart(pageForAnchor(pages, paragraph, anchorOffset), pages.size, visiblePageCount)
    }
}

internal data class MeasuredEInkChapter(val paragraphs: List<ReadingParagraph>, val layouts: Map<Int, StaticLayout>, val pages: List<StaticPage>)
private data class EInkMeasureInput(val paragraphs: List<ReadingParagraph>, val title: String, val typography: List<Any>, val width: Int, val height: Int, val density: Float, val fontScale: Float, val columns: Int)
private data class EInkMeasurement(val input: EInkMeasureInput, val chapter: MeasuredEInkChapter)

/** 在当前主题画笔上应用辅文本透明度，不保存固定颜色。 */
private class SecondaryOpacity(private val alpha: Float) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) { paint.alpha = (paint.alpha * alpha).toInt().coerceIn(0, 255) }
}

/**
 * 用 Android StaticLayout 一次测量整章，再把不可分的行交给纯分页算法。
 * width/height 为像素，字号沿用 Compose 的 sp 换算，保留系统非线性字体缩放；段距使用 dp。
 * 拼接标签、缩进和语言分隔符的顺序必须与 paragraphPartStarts 一致，才能复用搜索字符锚点。
 * 返回的段落、布局与页表属于同一测量快照，显示时不能与新一轮内容混用。
 */
internal fun measureEInkChapter(paragraphs: List<ReadingParagraph>, settings: ReaderSettings, width: Int, height: Int, density: Density,
    readerTypeface: Typeface = Typeface.SANS_SERIF, title: String = "", checkCancelled: () -> Unit = {}): MeasuredEInkChapter {
    val layouts = mutableMapOf<Int, StaticLayout>()
    val lines = mutableListOf<PageLine>()
    chapterHeading(title, paragraphs)?.let { heading ->
        checkCancelled()
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { (settings.fontSize * 1.25f).sp.toPx() }
            letterSpacing = settings.letterSpacing
            color = android.graphics.Color.BLACK
            typeface = Typeface.create(readerTypeface, Typeface.BOLD)
        }
        val layout = readerNativeLayout(heading, paint, width, settings.lineHeight)
        layouts[CHAPTER_HEADING_PARAGRAPH] = layout
        for(line in 0 until layout.lineCount) {
            checkCancelled()
            lines += PageLine(CHAPTER_HEADING_PARAGRAPH, layout.getLineStart(line), layout.getLineEnd(line),
                layout.getLineBottom(line) - layout.getLineTop(line))
        }
    }
    paragraphs.forEachIndexed { index, paragraph ->
        checkCancelled()
        if (paragraph.imageUrl != null || paragraph.localImageId != null) {
            lines += PageLine(index, 0, 0, height, image = true)
        } else {
            val text = SpannableStringBuilder()
            paragraph.parts.forEachIndexed { partIndex, part ->
                checkCancelled()
                if (partIndex > 0) text.append(READING_PART_SEPARATOR)
                val partStart = text.length
                if (settings.parallel && part.source in listOf("sakura", "gpt", "youdao")) text.append(part.source.uppercase()).append("\n")
                val start = text.length
                text.append(settings.indentText)
                val bodyStart = text.length
                text.append(part.text)
                val styledPart = part.withInlinePreferences(settings)
                text.addReaderInline(styledPart, bodyStart, width)
                if (part.secondary) {
                    text.setSpan(SecondaryOpacity(settings.secondaryAlpha), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(AbsoluteSizeSpan(with(density) { readerPartFontSize(settings, true).sp.roundToPx() }), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (settings.underline) text.setSpan(UnderlineSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if(text.length > partStart) text.setSpan(ReaderLineHeightSpan(
                    readerLineHeightPx(with(density) { readerPartFontSize(settings, part.secondary).sp.toPx() }, settings.lineHeight),
                    styledPart.inline.any { it.kind == cc.novelia.app.data.model.InlineKind.Ruby }),
                    partStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (text.isEmpty()) text.append(" ")
            val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = with(density) { settings.fontSize.sp.toPx() }
                letterSpacing = settings.letterSpacing
                color = android.graphics.Color.BLACK
                typeface = Typeface.create(readerTypeface, if (settings.weight) Typeface.BOLD else Typeface.NORMAL)
            }
            val layout = readerNativeLayout(text, paint, width, settings.lineHeight)
            layouts[index] = layout
            for (line in 0 until layout.lineCount) {
                if (line % 32 == 0) checkCancelled()
                lines += PageLine(index, layout.getLineStart(line), layout.getLineEnd(line), layout.getLineBottom(line) - layout.getLineTop(line))
            }
        }
    }
    val pairedParagraphs = paragraphs.indices.filterTo(mutableSetOf()) { paragraphs[it].parts.size > 1 }
    return MeasuredEInkChapter(paragraphs, layouts,
        paginateLines(lines, height, with(density) { settings.resolvedParagraphSpacing.dp.roundToPx() }, pairedParagraphs, checkCancelled))
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
    annotations: List<ReadingTextMatch> = emptyList(),
    highlightLayers: List<ReaderHighlightLayer> = listOfNotNull(activeMatch?.let { ReaderHighlightLayer(it, 1f) }),
    interactionEnabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(0.dp), onNavigate: () -> Unit = {},
    onNote: (String) -> Unit = {}, chapterTitle: String = "",
    pageColumns: Int = 1,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val columns = pageColumns.coerceIn(1, 2)
    val gutter = if(columns == 2) 16.dp else 0.dp
    val horizontalPadding = contentPadding.calculateLeftPadding(layoutDirection) + contentPadding.calculateRightPadding(layoutDirection)
    val context = LocalContext.current
    val font = rememberReaderFont(settings)
    var expandedImage by remember { mutableStateOf<Any?>(null) }
    val animate = !settings.eInkMode && !LocalEInkMode.current && !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()
    val turnDuration = if(columns == 2) AppMotion.Quick else AppMotion.Standard
    var pageOffset by remember { mutableFloatStateOf(0f) }
    var dragAxis by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var transitionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var previousPageIndex by remember(state.pages) { mutableIntStateOf(state.pageIndex) }
    var incomingOffset by remember { mutableFloatStateOf(0f) }
    var outgoingPageIndex by remember { mutableStateOf<Int?>(null) }
    var gestureTarget by remember { mutableStateOf<Int?>(null) }
    val dragScope = rememberCoroutineScope()
    val latestPage by rememberUpdatedState(onPage)
    val canInteract = interactionEnabled && expandedImage == null
    // 页码、排版或交互状态改变后，不允许旧动画继续提交到新页面。
    LaunchedEffect(state.pages, state.pageIndex, columns, animate, canInteract) {
        val from = previousPageIndex
        val direction = (state.pageIndex - from).coerceIn(-1, 1)
        val gestureCommitted = gestureTarget == state.pageIndex
        gestureTarget = null
        previousPageIndex = state.pageIndex
        transitionJob?.cancel()
        pageOffset = 0f
        outgoingPageIndex = null
        incomingOffset = 0f
        // 朗读、音量键和翻页按钮直接改变页码时，也走与手势一致的页面过渡。
        if(animate && canInteract && !gestureCommitted && abs(state.pageIndex - from) == state.visiblePageCount && state.pages.getOrNull(from) != null) {
            dragAxis = 0
            incomingOffset = direction.toFloat()
            outgoingPageIndex = from
            try {
                androidx.compose.animation.core.animate(direction.toFloat(), 0f,
                    animationSpec = tween(turnDuration, easing = FastOutSlowInEasing)) { value, _ -> incomingOffset = value }
            } finally { outgoingPageIndex = null; incomingOffset = 0f }
        }
    }
    val settle: (Int) -> Unit = { direction ->
        transitionJob?.cancel()
        val hasNeighbor = if(direction > 0) state.canGoForward else if(direction < 0) state.canGoBack else true
        if (!animate || (direction != 0 && !hasNeighbor)) {
            pageOffset = 0f
            if (direction != 0) latestPage(direction)
        } else {
            val start = pageOffset
            val target = if(direction == 0) 0f else if(direction > 0) -1f else 1f
            transitionJob = dragScope.launch {
                androidx.compose.animation.core.animate(start, target,
                    animationSpec = tween(if(direction == 0) AppMotion.Quick else turnDuration,
                        easing = FastOutSlowInEasing)) { value, _ -> pageOffset = value }
                if(direction != 0) {
                    gestureTarget = state.pageIndex + direction * state.visiblePageCount
                    latestPage(direction)
                }
                pageOffset = 0f
            }
        }
    }
    val latestSettle by rememberUpdatedState(settle)
    val tapNavigation = rememberReaderTapNavigation(settings.tapPageTurn, canInteract, onToggleMenu, { direction ->
        if(transitionJob?.isActive != true) {
            dragAxis = 0
            pageOffset = 0f
            settle(direction)
        }
    }, settings.resolvedTapZoneMap, onNavigate)
    BoxWithConstraints(
        modifier.background(background).clipToBounds()
            .readerTapFeedback(tapNavigation, foreground, settings.eInkMode || LocalEInkMode.current)
            .readerTapNavigation(tapNavigation)
            .pointerInput(settings.horizontalPageTurn, columns, animate, canInteract) {
                if(!canInteract || !settings.horizontalPageTurn) return@pointerInput
                var total = androidx.compose.ui.geometry.Offset.Zero
                var axisLocked = false
                val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectDragGestures(
                    onDragStart = {
                        transitionJob?.cancel()
                        total = androidx.compose.ui.geometry.Offset.Zero
                        axisLocked = false
                        velocity.resetTracking()
                    },
                    onDragCancel = { latestSettle(0) },
                    onDragEnd = {
                        val enabled = dragAxis == 0 && settings.horizontalPageTurn
                        val distance = if(dragAxis == 0) total.x else total.y
                        val speed = velocity.calculateVelocity().let { if(dragAxis == 0) it.x else it.y }
                        val fling = abs(speed) >= 750.dp.toPx() && abs(distance) >= viewConfiguration.touchSlop && speed * distance > 0f
                        val passed = abs(distance) >= 40.dp.toPx() || fling
                        val direction = if((if(fling) speed else distance) < 0) 1 else -1
                        latestSettle(if(enabled && tapNavigation.canSwipe && passed) direction else 0)
                    }
                ) { change, amount ->
                    if(!tapNavigation.canSwipe) return@detectDragGestures
                    change.consume()
                    if(!axisLocked) {
                        val axis = if(abs(amount.x) >= abs(amount.y)) 0 else 1
                        if(dragAxis != axis) pageOffset = 0f
                        dragAxis = axis
                        axisLocked = true
                    }
                    total += amount
                    velocity.addPosition(change.uptimeMillis, change.position)
                    val enabled = dragAxis == 0 && settings.horizontalPageTurn
                    if(animate && enabled) {
                        val extent = (if(dragAxis == 0) viewport.width else viewport.height).toFloat()
                        val delta = if(dragAxis == 0) amount.x else amount.y
                        if(extent > 0) {
                            val direction = if(pageOffset + delta / extent < 0) 1 else -1
                            val resistance = if(if(direction > 0) !state.canGoForward else !state.canGoBack) .15f else 1f
                            pageOffset = (pageOffset + delta * resistance / extent).coerceIn(-1f, 1f)
                        }
                    }
                }
            }
            // 手势覆盖整个阅读区；限宽只作用于居中的正文和分页测量。
            .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = settings.width.dp * columns + gutter).fillMaxWidth()
            .padding(if(columns == 1) contentPadding else PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())).clipToBounds()
    ) {
        val width = if(columns == 1) constraints.maxWidth.coerceAtLeast(1) else
            ((constraints.maxWidth - with(density) { gutter.roundToPx() }) / columns - with(density) { horizontalPadding.roundToPx() }).coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val typography = listOf(settings.fontSize, settings.lineHeight, settings.letterSpacing, settings.paragraphSpacing, settings.weight, settings.resolvedIndentSize, settings.parallel, settings.underline, settings.secondaryAlpha, font.typeface, font.loading,
            settings.showEpubRuby, settings.epubFootnotePopup, settings.showEpubStyles)
        val input = EInkMeasureInput(paragraphs, chapterTitle, typography, width, height, density.density, density.fontScale, columns)
        val measured by produceState<EInkMeasurement?>(null, input) {
            if(value != null) delay(120)
            if (!font.loading && width != Constraints.Infinity && height != Constraints.Infinity) {
                val next = withContext(Dispatchers.Default) {
                    val jobContext = currentCoroutineContext()
                    measureEInkChapter(paragraphs, settings, width, height, density, font.typeface, chapterTitle) { jobContext.ensureActive() }
                }
                state.install(next.pages, next.paragraphs, columns)
                value = EInkMeasurement(input, next)
            }
        }
        val chapter = measured?.takeIf { it.input == input && state.pages === it.chapter.pages }?.chapter
        SideEffect { if(chapter == null) state.invalidate() else state.confirm(chapter.pages) }
        if (chapter == null) Text("正在分页…", Modifier.align(Alignment.Center), color = foreground)
        else {
            BoxWithConstraints(Modifier.fillMaxSize().testTag(if(columns == 2) "reader-dual-page" else "reader-single-page").clipToBounds().onSizeChanged { viewport = it }) {
                val extent = (if(dragAxis == 0) constraints.maxWidth else constraints.maxHeight).toFloat()
                val shift = if(animate) pageOffset * extent else 0f
                val direction = when { shift < 0 -> 1; shift > 0 -> -1; else -> 0 }
                @Composable fun body(firstPage: Int, offset: Float, opacity: Float = 1f) {
                    val spreadModifier = Modifier.fillMaxSize().graphicsLayer {
                            translationX = if(columns == 1 && dragAxis == 0) offset else 0f
                            translationY = if(columns == 1 && dragAxis == 1) offset else 0f
                            alpha = opacity
                        }.background(background)
                    @Composable fun page(index: Int, modifier: Modifier) {
                        PageBody(chapter.pages.getOrNull(index), chapter, settings, foreground, highlightLayers, annotations, density, context,
                            tapNavigation, onSelect, imageModel, { expandedImage = it }, constraints.maxHeight,
                            { if (canInteract) { tapNavigation.cancel(); onNote(it) } }, modifier)
                    }
                    if(columns == 1) page(firstPage, spreadModifier)
                    else CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(spreadModifier) {
                            page(firstPage, Modifier.weight(1f).fillMaxHeight().padding(horizontal = horizontalPadding / 2).clipToBounds().testTag("reader-left-page"))
                            Box(Modifier.width(gutter).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                Box(Modifier.width(1.dp).fillMaxHeight().background(foreground.copy(alpha = .12f)))
                            }
                            page(firstPage + 1, Modifier.weight(1f).fillMaxHeight().padding(horizontal = horizontalPadding / 2).clipToBounds().testTag("reader-right-page"))
                        }
                    }
                }
                val outgoing = outgoingPageIndex
                if(columns == 2) {
                    // 折叠屏两侧的页框与中缝固定，正文在原位交叉淡化，不横跨折痕平移。
                    // 底层保持不透明，顶层连同纸张背景淡入，避免叠加透明度使背景闪烁。
                    if(animate && outgoing != null) {
                        body(outgoing, 0f)
                        body(state.pageIndex, 0f, (1f - abs(incomingOffset)).coerceIn(0f, 1f))
                    } else {
                        body(state.pageIndex, 0f)
                        val neighbor = state.pageIndex + direction * state.visiblePageCount
                        if(animate && direction != 0 && neighbor in chapter.pages.indices) {
                            body(neighbor, 0f, abs(pageOffset).coerceIn(0f, 1f))
                        }
                    }
                } else if(animate && outgoing != null) {
                    val pageDirection = (state.pageIndex - outgoing).coerceIn(-1, 1)
                    body(outgoing, (incomingOffset - pageDirection) * extent)
                    body(state.pageIndex, incomingOffset * extent)
                } else {
                    val neighbor = state.pageIndex + direction * state.visiblePageCount
                    if(direction != 0 && neighbor >= 0 && neighbor < chapter.pages.size) {
                        body(neighbor, shift + direction * extent)
                    }
                    body(state.pageIndex, shift)
                }
            }
        }
    }
    expandedImage?.let { model -> IllustrationViewer(model) { expandedImage = null } }
}

/**
 * 单页正文渲染，供跟手翻页同时绘制当前页与相邻页。
 *
 * 从 [EInkPage] 抽出：拖动时两页共存，必须把渲染逻辑做成可复用组件，
 * 各自持有独立的图片重试等局部状态。
 */
@Composable
internal fun PageBody(
    page: StaticPage?,
    chapter: MeasuredEInkChapter,
    settings: ReaderSettings,
    foreground: Color,
    highlightLayers: List<ReaderHighlightLayer>,
    annotations: List<ReadingTextMatch>,
    density: Density,
    context: android.content.Context,
    tapNavigation: ReaderTapNavigation,
    onSelect: (ReadingParagraph) -> Unit,
    imageModel: (ReadingParagraph) -> Any?,
    onExpandImage: (Any?) -> Unit,
    viewportHeight: Int,
    onNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {

    // 连续行带可能同时露出超大单行和相邻行；分别缩放超大行，不能把整段一起压缩。
    val groups = remember(page, viewportHeight) {
        val result = mutableListOf<MutableList<PageLine>>()
        page?.lines.orEmpty().forEach { line ->
            val last = result.lastOrNull()
            if(last == null || last.last().paragraph != line.paragraph || last.last().height > viewportHeight || line.height > viewportHeight)
                result += mutableListOf(line)
            else last += line
        }
        result
    }
    // 正文仅显示 readerTapFeedback 的分区提示，避免默认涟漪按容器大小给整页染色。
    Column(modifier.combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClickLabel = "显示或收起阅读工具栏", onClick = tapNavigation::click)) {
        groups.forEachIndexed { groupIndex, lines ->
            val index = lines.first().paragraph
            if(groupIndex > 0 && groups[groupIndex - 1].first().paragraph != index) Spacer(Modifier.height(settings.resolvedParagraphSpacing.dp))
            val paragraph = chapter.paragraphs.getOrNull(index)
            if (lines.first().image && paragraph != null) {
                val model = imageModel(paragraph)
                var retry by remember(model) { mutableIntStateOf(0) }
                val request = remember(context, model, retry) {
                    ImageRequest.Builder(context).data(model).setParameter("readerRetry", retry).crossfade(false).build()
                }
                var failed by remember(model) { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().height(with(density) { viewportHeight.toDp() }).combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = tapNavigation::click,
                    onLongClickLabel = "放大查看插图", onLongClick = { onExpandImage(model) }), contentAlignment = Alignment.Center) {
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
                val drawScale = if(lines.size == 1) (viewportHeight.toFloat() / sliceHeight).coerceAtMost(1f) else 1f
                val visibleText = layout.text.subSequence(lines.first().start, lines.last().end).toString()
                Canvas(Modifier.fillMaxWidth().height(with(density) { (sliceHeight * drawScale).toDp() }).clipToBounds()
                    .then(if(index == CHAPTER_HEADING_PARAGRAPH) Modifier.testTag("reader-page-chapter-title") else Modifier)
                    .semantics {
                        text = AnnotatedString(visibleText)
                        if(index == CHAPTER_HEADING_PARAGRAPH) heading()
                        val marked = layout.text as? Spanned
                        customActions = marked?.getSpans(lines.first().start, lines.last().end, ReaderNoteSpan::class.java).orEmpty().map { note ->
                            val label = marked!!.subSequence(marked.getSpanStart(note), marked.getSpanEnd(note))
                            CustomAccessibilityAction("查看脚注 $label") { onNote(note.body); true }
                        }
                    }
                    .then(if(paragraph == null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "显示或收起阅读工具栏", onClick = tapNavigation::click)
                    else Modifier.readerInlineActions({ position ->
                        val note = position?.let { layout.noteAt(it.x / drawScale, it.y / drawScale + top) }
                        if (note != null) onNote(note) else tapNavigation.click()
                    }, { onSelect(paragraph) }))) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.scale(drawScale, drawScale)
                        native.translate(0f, -top.toFloat())
                        val annotationSpans = mutableListOf<UnderlineSpan>()
                        val spannable = layout.text as? android.text.Spannable
                        if(paragraph != null) {
                            annotations.filter { it.paragraph == index }.forEach { match ->
                                val start = match.textOffset(paragraph, settings).coerceIn(0, layout.text.length)
                                val end = (start + match.end - match.start).coerceIn(start, layout.text.length)
                                val path = android.graphics.Path()
                                layout.readerSelectionPath(start, end, path)
                                native.drawPath(path, android.graphics.Paint().apply { color = foreground.copy(alpha = .1f).toArgb() })
                                if(spannable != null) UnderlineSpan().also {
                                    spannable.setSpan(it, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); annotationSpans += it
                                }
                            }
                            highlightLayers.filter { it.match.paragraph == index && it.match.end > it.match.start }.forEach { layer ->
                                val match = layer.match
                                val start = match.textOffset(paragraph, settings).coerceIn(0, layout.text.length)
                                val end = (start + match.end - match.start).coerceIn(start, layout.text.length)
                                val path = android.graphics.Path()
                                layout.getSelectionPath(start, end, path)
                                native.drawPath(path, android.graphics.Paint().apply { color = foreground.copy(alpha = .2f * layer.alpha).toArgb() })
                            }
                        }
                        layout.paint.color = foreground.toArgb()
                        try { layout.draw(native) } finally { annotationSpans.forEach { spannable?.removeSpan(it) } }
                        native.restore()
                    }
                }
            }
        }
        if (page?.lines.isNullOrEmpty()) Text("本章暂无正文", color = foreground)
    }
}
