@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppTextButton
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.media.IllustrationViewer
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.text
import kotlinx.coroutines.launch

/**
 * 静态分页的位置状态，适用于普通屏幕和电子纸。页码是测量结果，持久定位使用段落及字符锚点。
 * install 在字体/视口变化后重新找页，move 才把锚点推进到用户实际翻到的页首，
 * 避免连续多次中间测量把位置逐步向前“吸附”。ready 为 false 时禁止按旧页表翻页。
 */
@Stable internal class RowPagingState(initial: Position?) : ReaderPager {
    // 渲染器将此列表实例绑定到已测量文本快照；即使新测量的行值相等，
    // 也须替换旧列表实例，避免关联到旧快照。
    override var pages by mutableStateOf<List<StaticPage>>(emptyList(), referentialEqualityPolicy())
        private set
    override var pageIndex by mutableIntStateOf(0)
        private set
    override var ready by mutableStateOf(false)
        private set
    private var anchorParagraph = ((initial?.index ?: 1) - 1).coerceAtLeast(0)
    private var anchorOffset = initial?.textOffset ?: 0
    private var sourceParagraph: Int? = null
    private var content = emptyList<ReadingParagraph>()
    val current get() = pages.getOrNull(pageIndex)
    override val paragraph get() = current?.lines?.firstOrNull()?.paragraph ?: anchorParagraph
    override val textOffset get() = current?.lines?.firstOrNull()?.start ?: anchorOffset
    override val sourceIndex get() = content.getOrNull(paragraph)?.index ?: sourceParagraph
    override val canGoBack get() = pageIndex > 0
    override val canGoForward get() = pageIndex + 1 < pages.size

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
    override fun invalidate() { ready = false }
    override fun move(direction: Int) {
        if (ready && pages.isNotEmpty()) {
            pageIndex = (pageIndex + direction).coerceIn(0, pages.lastIndex)
            anchorParagraph = paragraph
            anchorOffset = textOffset
            sourceParagraph = content.getOrNull(anchorParagraph)?.index
        }
    }
    /** 纵向连续滚动落定时静默对齐页码：只移动索引与锚点，不动纸带像素游标。 */
    fun seek(newIndex: Int) {
        if (!ready || pages.isEmpty()) return
        val target = newIndex.coerceIn(0, pages.lastIndex)
        if (target == pageIndex) return
        pageIndex = target
        anchorParagraph = paragraph
        anchorOffset = textOffset
        sourceParagraph = content.getOrNull(anchorParagraph)?.index
    }
    override fun find(paragraph: Int, offset: Int, source: Int?) {
        anchorParagraph = paragraph
        anchorOffset = offset.coerceAtLeast(0)
        sourceParagraph = source ?: content.getOrNull(paragraph)?.index
        pageIndex = pageForAnchor(pages, paragraph, anchorOffset)
    }
}

internal data class MeasuredRowChapter(val paragraphs: List<ReadingParagraph>, val layouts: Map<Int, StaticLayout>, val pages: List<StaticPage>,
    /** 整章行序列：一条连续纸带，纵向滚动与翻页快照都从它烘焙。 */
    val allLines: List<PageLine> = emptyList())
private data class RowMeasureInput(val paragraphs: List<ReadingParagraph>, val typography: List<Any>, val width: Int, val height: Int, val density: Float, val fontScale: Float)
private data class RowMeasurement(val input: RowMeasureInput, val chapter: MeasuredRowChapter)

/** 在当前主题画笔上应用辅文本透明度，不保存固定颜色。 */
private class RowSecondaryOpacity(private val alpha: Float) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) { paint.alpha = (paint.alpha * alpha).toInt().coerceIn(0, 255) }
}

/**
 * 用 Android StaticLayout 一次测量整章，再把不可分的行交给纯分页算法。
 * width/height 为像素，字号需乘 density 与 fontScale，段距只乘 density。
 * 拼接标签、缩进和语言分隔符的顺序必须与 paragraphPartStarts 一致，才能复用搜索字符锚点。
 * 返回的段落、布局与页表属于同一测量快照，显示时不能与新一轮内容混用。
 */
internal fun measureRowPagedChapter(paragraphs: List<ReadingParagraph>, settings: ReaderSettings, width: Int, height: Int, density: Float, fontScale: Float,
    readerTypeface: Typeface = Typeface.SANS_SERIF, checkCancelled: () -> Unit = {}): MeasuredRowChapter {
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
                    text.setSpan(RowSecondaryOpacity(settings.secondaryAlpha), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(AbsoluteSizeSpan((readerPartFontSize(settings, true) * density * fontScale).toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (settings.underline) text.setSpan(UnderlineSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (text.isEmpty()) text.append(" ")
            val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = settings.fontSize * density * fontScale
                color = android.graphics.Color.BLACK
                typeface = Typeface.create(readerTypeface, if (settings.weight) Typeface.BOLD else Typeface.NORMAL)
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
    return MeasuredRowChapter(paragraphs, layouts,
        paginateLines(lines, height, (settings.resolvedParagraphSpacing * density).roundToInt(), pairedParagraphs, checkCancelled),
        allLines = lines.toList())
}

/** 行纸带的累计几何：行顶（含前置段距）与行底，均相对整章 0 点。 */
private class LineMetrics(lines: List<PageLine>, spacingPx: Float) {
    private val cum = FloatArray(lines.size + 1)
    val total: Float
    init {
        var t = 0f
        for (i in lines.indices) {
            if (i > 0 && lines[i].paragraph != lines[i - 1].paragraph) t += spacingPx
            cum[i] = t
            t += lines[i].height
        }
        cum[lines.size] = t
        total = t
    }
    fun yTop(i: Int) = cum[i.coerceIn(0, cum.size - 1)]
    /** 二分：行顶恰好 <= y 的最大行。 */
    fun rowAt(y: Float): Int {
        if (cum.size <= 1) return 0
        var lo = 0; var hi = cum.size - 2; var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= y) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/**
 * 整章行纸带绘制：实时渲染与翻页位图共用同一段代码，
 * 保证「位图动画终点的像素」与「提交后纸带的像素」逐像素一致——
 * 这是翻页提交不闪的根据（FBReader 位图模型的无缝条件）。
 *
 * 纵向位置用**相对累加**而不是 metrics 绝对行顶：
 * paginateLines 在页首不计段距（page.isNotEmpty() 才加 gap），
 * 若这里用绝对行顶，每页会多算一个段距 → 比页表少显示一行 → 页码与内容错位（串行）。
 */
private fun drawStrip(canvas: android.graphics.Canvas, fromRow: Int, toRow: Int,
    chapter: MeasuredRowChapter, settings: ReaderSettings, spacingPx: Float,
    foregroundArgb: Int, activeMatch: ReadingTextMatch?, heightPx: Int) {
    val lines = chapter.allLines
    var y = 0f
    for (i in fromRow until toRow) {
        val line = lines[i]
        // 页首行不加段距，与 paginateLines 的 used 累加严格一致
        if (i > fromRow && line.paragraph != lines[i - 1].paragraph) y += spacingPx
        if (line.image) {
            val box = Paint().apply { color = android.graphics.Color.argb(40, 127, 127, 127) }
            canvas.drawRect(0f, y, canvas.width.toFloat(), (y + line.height).coerceAtMost(heightPx.toFloat()), box)
        } else {
            val layout = chapter.layouts[line.paragraph] ?: continue
            val drawScale = (heightPx.toFloat() / line.height).coerceAtMost(1f)
            val lt = layout.getLineTop(layout.getLineForOffset(line.start))
            val save = canvas.save()
            canvas.scale(drawScale, drawScale)
            canvas.translate(0f, y / drawScale - lt)
            activeMatch?.takeIf { it.paragraph == line.paragraph && it.end > it.start }?.let { match ->
                val s = match.textOffset(chapter.paragraphs[line.paragraph], settings).coerceIn(0, layout.text.length)
                val e = (s + match.end - match.start).coerceIn(s, layout.text.length)
                val path = android.graphics.Path()
                layout.getSelectionPath(s, e, path)
                canvas.drawPath(path, Paint().apply { color = (foregroundArgb and 0x00FFFFFF) or (51 shl 24) })
            }
            layout.paint.color = foregroundArgb
            layout.draw(canvas)
            canvas.restoreToCount(save)
        }
        y += line.height
    }
}

/** 横向翻页快照（FBReader AnimationProvider 模型）：两张整屏位图，动画只搬运像素。 */
private class PageTurn(val dir: Int, val from: Bitmap, val to: Bitmap, val goalTop: Float) {
    /** 动画已走完、但快照仍持有：画面停在终点帧，不切分支（防旧页诈尸）。 */
    var settled = false
}

/**
 * 阅读器分页正文。模型：整章 = 一条连续行纸带 + 一个像素游标 viewportTop。
 * - 纵向：纸带连续滚动，滑多少走多少、可停在任意像素；落定按视口位置对齐页码，
 *   翻页后继续滚就地在下一页上推进——不存在整页跳、不存在空白黑底、不存在字被切一半，
 *   因为纸上永远铺满真实连续文字（FBReader 行重叠语义的推广）。
 * - 横向与点击：位图快照翻页。目标页钉死原位压暗，当前页跟手指掀开（slide 语义），
 *   走满终点像素与提交后纸带 100% 重合，提交在同一快照写入里完成，无空帧无闪烁。
 */
@Composable internal fun RowPagedReader(
    paragraphs: List<ReadingParagraph>, settings: ReaderSettings, state: RowPagingState,
    modifier: Modifier = Modifier, imageModel: (ReadingParagraph) -> Any?,
    onToggleMenu: () -> Unit, onSelect: (ReadingParagraph) -> Unit, onPage: (Int) -> Unit,
    background: Color = Color.White, foreground: Color = Color.Black,
    activeMatch: ReadingTextMatch? = null,
    interactionEnabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(0.dp), onNavigate: () -> Unit = {},
    onSavePosition: () -> Unit = {}, menuOpen: Boolean = false,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val font = rememberReaderFont(settings)
    var expandedImage by remember { mutableStateOf<Any?>(null) }
    val animate = !settings.eInkMode && !LocalEInkMode.current && !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()
    val densityValue = density.density
    val latestPage by rememberUpdatedState(onPage)
    val latestSave by rememberUpdatedState(onSavePosition)
    val scope = rememberCoroutineScope()

    // 纸带像素游标（章内绝对位置，0 = 章首行顶）。
    var viewportTop by remember { mutableFloatStateOf(0f) }
    // 翻页快照：非空即处于拖动/动画中（屏幕画位图）；turnShift 为当前位移。
    var turn by remember { mutableStateOf<PageTurn?>(null) }
    var turnShift by remember { mutableFloatStateOf(0f) }
    var turnJob by remember { mutableStateOf<Job?>(null) }
    // 提交后冻结显示的那一帧位图及其对应的纸带游标。
    // 位图与实时绘制是两套渲染器（色深/抗锯齿不同），切换必然露馅；
    // 冻结到下一次交互，画面就与动画最后一帧逐像素相同（FBReader onDrawStatic 同款）。
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var frozenTop by remember { mutableFloatStateOf(-1f) }
    // 复用位图缓冲：拖动第一帧若现分配两块 27MB，会卡出一帧，被看成闪烁。
    // 0 = from，1 = to，2 = 冻结帧。
    val slots = remember { arrayOfNulls<Bitmap>(3) }
    // 点击翻页请求（方向+时间戳）：需要视口尺寸与快照，交给内层 LaunchedEffect。
    var tapTurn by remember { mutableStateOf<Pair<Int, Long>?>(null) }

    val tapNavigation = rememberReaderTapNavigation(
        settings.tapPageTurn,
        interactionEnabled && expandedImage == null,
        onToggleMenu,
        { dir -> if (animate && dir != 0) tapTurn = dir to System.nanoTime() else latestPage(dir) },
        settings.resolvedTapZoneMap,
        onNavigate,
        menuOpen
    )

    BoxWithConstraints(
        modifier.background(background).clipToBounds()
            .readerTapFeedback(tapNavigation, foreground, settings.eInkMode || LocalEInkMode.current)
            .readerTapNavigation(tapNavigation)
            .padding(contentPadding).clipToBounds()
    ) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val typography = listOf(settings.fontSize, settings.lineHeight, settings.paragraphSpacing, settings.weight, settings.indent, settings.parallel, settings.underline, settings.secondaryAlpha, font.typeface, font.loading)
        val input = RowMeasureInput(paragraphs, typography, width, height, density.density, density.fontScale)
        val measured by produceState<RowMeasurement?>(null, input) {
            if(value != null) delay(120)
            if (!font.loading && width != Constraints.Infinity && height != Constraints.Infinity) {
                val next = withContext(Dispatchers.Default) {
                    val jobContext = currentCoroutineContext()
                    measureRowPagedChapter(paragraphs, settings, width, height, density.density, density.fontScale, font.typeface) { jobContext.ensureActive() }
                }
                state.install(next.pages, next.paragraphs)
                value = RowMeasurement(input, next)
            }
        }
        val chapter = measured?.takeIf { it.input == input && state.pages === it.chapter.pages }?.chapter
        SideEffect { if(chapter == null) state.invalidate() }
        if (chapter == null) Text("正在分页…", Modifier.align(Alignment.Center), color = foreground)
        else {
            val viewportW = width.toFloat()
            val viewportH = height.toFloat()
            val spacingPx = with(density) { settings.resolvedParagraphSpacing.dp.toPx() }
            val lines = chapter.allLines
            val metrics = remember(chapter, spacingPx) { LineMetrics(lines, spacingPx) }
            val maxTop = (metrics.total - viewportH).coerceAtLeast(0f)
            // 每页首行在纸带上的行索引与行顶（单调递增，页序=行序）。
            val pageStartRow = remember(chapter, lines) {
                val firstRowOf = HashMap<Pair<Int, Int>, Int>(lines.size * 2)
                lines.forEachIndexed { i, l -> firstRowOf.putIfAbsent(l.paragraph to l.start, i) }
                IntArray(chapter.pages.size) { pi ->
                    chapter.pages[pi].lines.firstOrNull()?.let { firstRowOf[it.paragraph to it.start] } ?: -1
                }
            }
            fun pageStartY(pageIndex: Int): Float? = pageStartRow.getOrNull(pageIndex)?.takeIf { it >= 0 }?.let(metrics::yTop)
            /** 行号落在哪一页（单调扫描，越界钳位）。 */
            fun pageAtRow(row: Int): Int {
                var ans = 0
                for (i in pageStartRow.indices) {
                    val r = pageStartRow[i]
                    if (r in 0..row) ans = i else if (r > row) break
                }
                return ans
            }
            val page = state.current

            // 章节切换：清掉所有翻页快照与冻结位图，按新章节重新定位
            LaunchedEffect(chapter) {
                turn = null
                turnShift = 0f
                frozen = null
                frozenTop = -1f
                viewportTop = (pageStartY(state.pageIndex) ?: 0f).coerceAtMost(maxTop)
            }
            // 已注释：页码只是「游标落在哪一页」的标签，不是真相源。
            // 这段事后回拉会把纵向滑到的行位置打回页顶（位置记不住），
            // 还会让刚提交的冻结位图立刻失效（翻完再闪）。真相源只有 viewportTop。
            // LaunchedEffect(state.pageIndex) {
            //     if (turn != null || frozen != null) return@LaunchedEffect
            //     val top = pageStartY(state.pageIndex) ?: return@LaunchedEffect
            //     val row = metrics.rowAt(viewportTop + viewportH * 0.4f)
            //     if (pageAtRow(row) != state.pageIndex) viewportTop = top.coerceAtMost(maxTop)
            // }

            // 已注释：冻结位图由交互主动释放（onDragStart / tapTurn），
            // 这里按游标差值被动清除会和提交时序抢跑，反而制造闪帧。
            // LaunchedEffect(viewportTop) {
            //     if (frozen != null && abs(viewportTop - frozenTop) > 0.5f) {
            //         frozen = null
            //         frozenTop = -1f
            //     }
            // }

            /**
             * 从 fromRow 起能完整容纳的行区间 [fromRow, toRow)。
             * 累加规则与 paginateLines / drawStrip 严格一致：
             * 页首行不计段距，其余段首加 spacingPx，只收完整行
             * （视口底部留白 < 一行高，绝不切行）。
             */
            fun screenRows(fromRow: Int): Pair<Int, Int> {
                var toRow = fromRow
                var used = 0f
                while (toRow < lines.size) {
                    val line = lines[toRow]
                    val gap = if (toRow > fromRow && line.paragraph != lines[toRow - 1].paragraph) spacingPx else 0f
                    if (used + gap + line.height > viewportH) break
                    used += gap + line.height
                    toRow++
                }
                if (toRow == fromRow && fromRow < lines.size) toRow = fromRow + 1
                return fromRow to toRow
            }

            /**
             * 复用缓冲烘焙：拖动时反复换方向会重复烘焙，
             * 每次新建两块 27MB 位图会卡出一帧（观感即闪烁）。
             * 槽位 0=from、1=to 循环复用，尺寸不符时才重建。
             */
            fun bakeInto(slot: Int, top: Float): Bitmap {
                val old = slots[slot]
                val bmp = if (old != null && !old.isRecycled && old.width == width && old.height == height) old
                    else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.density = context.resources.displayMetrics.densityDpi
                        slots[slot] = it
                    }
                val c = android.graphics.Canvas(bmp)
                c.drawColor(background.toArgb())
                val t = top.coerceIn(0f, maxTop)
                val (fromRow, toRow) = screenRows(metrics.rowAt(t))
                drawStrip(c, fromRow, toRow, chapter, settings, spacingPx, foreground.toArgb(), activeMatch, height)
                return bmp
            }

            fun bake(top: Float): Bitmap {
                // ARGB_8888：与实时 StaticLayout 绘制同色深，切换时不留色阶差
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // 位图密度必须与屏幕一致：默认 160dpi 会被 drawBitmap 按密度放大，
                // 与实时绘制差一个缩放系数，切换瞬间整页轻微位移=闪。
                bmp.density = context.resources.displayMetrics.densityDpi
                val c = android.graphics.Canvas(bmp)
                c.drawColor(background.toArgb())
                val t = top.coerceIn(0f, maxTop)
                val (fromRow, toRow) = screenRows(metrics.rowAt(t))
                drawStrip(c, fromRow, toRow, chapter, settings, spacingPx, foreground.toArgb(), activeMatch, height)
                return bmp
            }

            /**
             * 行单位的翻页目标：前进 = 当前屏底行的下一行起新的一屏，
             * 后退 = 按当前屏行数往前一屏。起点永远是「正在看的屏」而非页表，
             * 纵向滚到哪、左右翻页就衔接哪，不会按旧页表跳走造成重复或倒退。
             * 目标用「行索引」表达（不是像素），返回给调用方换算 yTop，
             * 避免绝对行顶与相对累加两套坐标互相打架。
             * 越出章界返回 null（交外层跨章）。
             */
            fun goalRowFor(dir: Int): Int? {
                // 容差 +0.5px：viewportTop 是浮点累积行顶，直接 rowAt 可能因误差
                // 退到上一行，导致每次翻页错一行、反复翻叠成「顶部行漂移」。
                val (fromRow, toRow) = screenRows(metrics.rowAt(viewportTop + 0.5f))
                return if (dir > 0) {
                    if (toRow >= lines.size) null else toRow
                } else {
                    if (fromRow == 0) null else (fromRow - (toRow - fromRow)).coerceAtLeast(0)
                }
            }

            /** 目标行的纸带游标（绝对像素），供 bake / 动画使用。 */
            fun goalTopFor(dir: Int): Float? {
                val row = goalRowFor(dir) ?: return null
                // 不能 coerce 到 maxTop：那会把行边界截断成非行边界，
                // 后续 rowAt 再也对不齐，同样造成漂移。允许末行顶部越界。
                val top = metrics.yTop(row).coerceAtLeast(0f)
                return top.takeIf { abs(it - viewportTop) > 0.5f }
            }

            // 位移动画 from → 终点；commit=true 走满整屏，在同一快照里
            // 「对齐游标 + 提交页码 + 清快照」，像素无缝；回弹则恢复原位清快照。
            fun runTurn(dir: Int, from: Float, commit: Boolean, duration: Int) {
                turnJob?.cancel()
                turnJob = scope.launch {
                    val goal = if (commit) (if (dir > 0) -viewportW else viewportW) else 0f
                    val nanos = duration.coerceAtLeast(60) * 1_000_000L
                    var t0 = 0L
                    var done = false
                    while (!done) {
                        withFrameNanos { now ->
                            if (t0 == 0L) t0 = now
                            val f = ((now - t0).toFloat() / nanos).coerceIn(0f, 1f)
                            turnShift = from + (goal - from) * FastOutSlowInEasing.transform(f)
                            if (f >= 1f) done = true
                        }
                    }
                    // 收尾：**不切分支**。
                    // 真机上「A 诈尸」的成因是：动画结束瞬间 turn 由非空变空，
                    // 绘制从动画分支跳到静态分支，荣耀合成器在该帧仍取旧 DisplayList
                    //（含被推走的 A），于是 A 闪现一帧。
                    // 这里让快照继续持有、turnShift 停在终点（A 已在屏幕外），
                    // 后续每帧仍走同一条动画分支，画面就是纯 B 位图，绘制路径零变化。
                    // 真正清理推迟到下一次手势，那时用户正在交互，察觉不到。
                    if (commit) {
                        val settled = turn
                        val g = settled?.goalTop
                        if (settled != null && g != null) {
                            Snapshot.withMutableSnapshot {
                                viewportTop = g
                                state.seek(pageAtRow(metrics.rowAt(g + viewportH * 0.4f)))
                                // 冻结终点位图：下一次清快照时静态分支画的就是它，像素一致
                                frozen = settled.to
                                frozenTop = g
                                settled.settled = true
                            }
                            latestSave()
                        } else {
                            Snapshot.withMutableSnapshot { turn = null; turnShift = 0f }
                            latestPage(dir)   // 章界：外层换章后由定位 effect 重置视图
                        }
                    } else {
                        Snapshot.withMutableSnapshot { turn = null; turnShift = 0f }
                    }
                }
            }

            // 点击：与滑动完全同一条位图路径（FBReader 语义）。
            LaunchedEffect(tapTurn) {
                val req = tapTurn ?: return@LaunchedEffect
                tapTurn = null
                val cur = turn
                // 动画进行中忽略点击；已停在终点（settled）则可以继续翻
                if (cur != null && !cur.settled) return@LaunchedEffect
                if (turnJob?.isActive == true) return@LaunchedEffect
                // 释放终态快照：静态分支画冻结位图（就是刚看到的 B），像素不变
                turn = null
                turnShift = 0f
                val g = goalTopFor(req.first)
                if (g == null) { latestPage(req.first); return@LaunchedEffect }
                if (!animate) {
                    Snapshot.withMutableSnapshot {
                        viewportTop = g
                        state.seek(pageAtRow(metrics.rowAt(g + viewportH * 0.4f)))
                    }
                    latestSave()
                    return@LaunchedEffect
                }
                // 点击翻页同样复用缓冲：主线程新建两块 27MB 会卡出一帧（观感即闪）
                val from = bakeInto(0, viewportTop)
                val to = bakeInto(1, g)
                turn = PageTurn(req.first, from, to, g)
                runTurn(req.first, 0f, true, AppMotion.Standard)
            }

            // ====== 手势层 ======
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(animate, chapter, settings.horizontalPageTurn, settings.scrollPageTurn) {
                        var axis = 0
                        var locked = false
                        var startX = 0f
                        var startY = 0f
                        var totalX = 0f
                        var totalY = 0f
                        var lastTime = 0L
                        var velX = 0f
                        var velY = 0f
                        detectDragGestures(
                            onDragStart = { p ->
                                startX = p.x; startY = p.y
                                totalX = 0f; totalY = 0f; axis = 0; locked = false
                                lastTime = 0L; velX = 0f; velY = 0f
                                turnJob?.cancel()   // 动画中途按下：冻结现位移继续拖
                                // 停在终点的快照（settled）在这里释放：静态分支会画
                                // 冻结位图（就是刚才那张 B），画面不变。
                                if (turn?.settled == true) { turn = null; turnShift = 0f }
                                // frozen 不能清：静态分支靠它保证按下瞬间画面不变。
                            },
                            onDragCancel = {
                                val t = turn
                                if (t != null && t.dir != 0) runTurn(t.dir, turnShift, false, AppMotion.Quick)
                            },
                            onDragEnd = {
                                val t = turn
                                if (axis == 0) {
                                    // 位移 56dp 或明显甩动（0.45px/ms≈450px/s）才翻页；
                                    // 原来的 40dp + 0.2px/ms 对轻点太灵敏，会误触发。
                                    val passed = abs(totalX) >= 56f * densityValue || abs(velX) >= 0.45f
                                    if (t != null) {
                                        val commit = passed && (if (totalX < 0f) t.dir > 0 else t.dir < 0)
                                        val moved = abs(turnShift) / viewportW
                                        runTurn(t.dir, turnShift, commit,
                                            if (commit) (AppMotion.Standard * (1f - moved)).toInt().coerceIn(80, AppMotion.Standard) else AppMotion.Quick)
                                    } else if (passed) {
                                        latestPage(if (totalX < 0f) 1 else -1)   // 章界滑动：跨章
                                    }
                                } else if (axis == 1 && locked && settings.scrollPageTurn) {
                                    // 纵向：惯性继续推进（纸带钳到章界），随后对齐页码
                                    val v = velY
                                    turnJob?.cancel()
                                    turnJob = scope.launch {
                                        if (animate && abs(v) >= 0.15f) {
                                            var vel = -v * 16.67f                 // px/ms → px/帧
                                            var pos = viewportTop
                                            var last = 0L
                                            while (abs(vel) > 1.2f) {
                                                withFrameNanos { now ->
                                                    if (last != 0L) {
                                                        pos = (pos + vel).coerceIn(0f, maxTop)
                                                        vel *= 0.94f
                                                    }
                                                    last = now
                                                    viewportTop = pos
                                                }
                                            }
                                        }
                                        // 落定：吸附行边界、就地页对齐，并把锚点交给外层持久化。
                                        // 只传 0（保存当前位），绝不能再传 ±1——外层 page(dir)
                                        // 会按页表 move，把刚滚出的行位置拉回页顶（翻页效果丢失）。
                                        val r = metrics.rowAt(viewportTop + 0.5f)
                                        val y0 = metrics.yTop(r)
                                        val y1 = metrics.yTop(r + 1)
                                        val pos = (if (viewportTop - y0 <= (y1 - y0) / 2f) y0 else y1).coerceIn(0f, maxTop)
                                        viewportTop = pos
                                        // 纵向落定也烘一张位图并冻结：静止画面统一由位图提供，
                                        // 与横向翻页的提交帧同源，杜绝「位图↔实时」切换差。
                                        frozen = bakeInto(2, pos)
                                        frozenTop = pos
                                        val pageIdx = pageAtRow(metrics.rowAt(pos + viewportH * 0.4f))
                                        if (pageIdx != state.pageIndex) state.seek(pageIdx)
                                        // 只保存，不经过外层 page(dir) 的页表 move——
                                        // 那会把锚点重置回页顶，等于丢掉刚滚出来的行位置。
                                        latestSave()
                                    }
                                }
                            }
                        ) { change, amount ->
                            change.consume()
                            if (!animate) return@detectDragGestures
                            if (!locked) {
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                if (dx * dx + dy * dy < 144f) return@detectDragGestures
                                axis = if (abs(dx) >= abs(dy)) 0 else 1
                                val enabled = if (axis == 0) settings.horizontalPageTurn else settings.scrollPageTurn
                                if (!enabled) return@detectDragGestures
                                locked = true
                                // 真正开始拖动才释放冻结位图（单击不经过这里），
                                // 否则纵向跟手会被上一帧的静态位图挡住。
                                frozen = null
                                frozenTop = -1f
                            }
                            if (axis == 0) {
                                totalX += amount.x
                                val dir = if (totalX < 0f) 1 else -1
                                val t = turn
                                if (t == null || t.dir != dir) {
                                    val g = goalTopFor(dir)
                                    if (g != null) {
                                        // 复用两块缓冲，避免每次换向都新建 54MB
                                        val from = bakeInto(0, viewportTop)
                                        val to = bakeInto(1, g)
                                        turn = PageTurn(dir, from, to, g)
                                        turnShift = totalX
                                    } else if (t != null) { turn = null; turnShift = 0f }
                                } else {
                                    turnShift = totalX
                                    // 回越过界：释放快照回到无动画状态
                                    val g = goalTopFor(dir)
                                    if (g == null) { turn = null; turnShift = 0f }
                                }
                            } else {
                                totalY += amount.y
                                viewportTop = (viewportTop - amount.y).coerceIn(0f, maxTop)
                            }
                            val now = change.uptimeMillis
                            if (lastTime != 0L && now > lastTime) {
                                val dt = (now - lastTime).toFloat()
                                velX = velX * 0.5f + amount.x / dt * 0.5f
                                velY = velY * 0.5f + amount.y / dt * 0.5f
                            }
                            lastTime = now
                        }
                    }
                    .pointerInput(chapter) {
                        // 长按选段：必须只观察、不消费事件。
                        // 用 detectTapGestures 会消费 up，外层 readerTapNavigation 的
                        // combinedClickable 就收不到点击 → 点击翻页与菜单开关全部失效。
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val slop = viewConfiguration.touchSlop
                            val holdMs = viewConfiguration.longPressTimeoutMillis
                            var fired = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if ((change.position - down.position).getDistance() > slop) break
                                if (!fired && change.uptimeMillis - down.uptimeMillis >= holdMs) {
                                    fired = true
                                    val row = metrics.rowAt((viewportTop + change.position.y).coerceAtMost(maxTop))
                                    lines.getOrNull(row)?.let { onSelect(chapter.paragraphs[it.paragraph]) }
                                }
                            }
                        }
                    }
            ) {
                val t = turn
                val (fromRow, toRow) = screenRows(metrics.rowAt(viewportTop + 0.5f))
                val showFrozen = t == null && frozen != null && abs(viewportTop - frozenTop) < 0.5f
                // 动画 / 冻结 / 实时三种绘制必须在同一个 Canvas 节点里分支。
                // 若拆成两个 Canvas（if turn != null 一个、else 一个），`turn` 由非空变空时
                // Compose 会销毁旧节点、新建节点，荣耀的合成器在这一帧仍取旧 DisplayList，
                // 表现为「动画结束后旧页 A 诈尸一帧，然后消失」。
                Canvas(Modifier.fillMaxSize().background(background)
                    .semantics {
                        text = AnnotatedString(page?.let { p ->
                            val layout = chapter.layouts[p.lines.firstOrNull()?.paragraph ?: 0]
                            val first = p.lines.firstOrNull()
                            if (layout != null && first != null)
                                layout.text.subSequence(first.start, first.end).toString() else ""
                        } ?: "")
                    }) {
                    drawIntoCanvas { dc ->
                        val nc = dc.nativeCanvas
                        if (t != null) {
                            // 动画帧：贴两张位图 + 覆盖页压暗 + 接缝渐变阴影
                            val progress = (abs(turnShift) / viewportW).coerceIn(0f, 1f)
                            nc.drawBitmap(t.to, 0f, 0f, null)
                            val dim = (0.44f * (1f - progress) * 255f).toInt().coerceIn(0, 255)
                            if (dim > 0) nc.drawColor(android.graphics.Color.argb(dim, 0, 0, 0))
                            val save = nc.save()
                            nc.translate(turnShift, 0f)
                            nc.drawBitmap(t.from, 0f, 0f, null)
                            nc.restoreToCount(save)
                            val shadow = 16f * densityValue * (1f - progress)
                            val paint = Paint()
                            if (shadow > 0.5f && turnShift < 0f) {
                                val seam = viewportW + turnShift
                                paint.shader = LinearGradient(seam, 0f, seam + shadow, 0f, 0x46000000.toInt(), 0, Shader.TileMode.CLAMP)
                                nc.drawRect(seam, 0f, seam + shadow, viewportH, paint)
                            } else if (shadow > 0.5f && turnShift > 0f) {
                                paint.shader = LinearGradient(turnShift - shadow, 0f, turnShift, 0f, 0, 0x46000000.toInt(), Shader.TileMode.CLAMP)
                                nc.drawRect(turnShift - shadow, 0f, turnShift, viewportH, paint)
                            }
                            paint.shader = null
                        } else if (showFrozen) {
                            // 提交后的静止帧：与动画终点同一张位图，像素完全一致
                            nc.drawBitmap(frozen!!, 0f, 0f, null)
                        } else {
                            nc.drawColor(background.toArgb())
                            drawStrip(nc, fromRow, toRow, chapter, settings, spacingPx, foreground.toArgb(), activeMatch, height)
                        }
                    }
                }
                // 视口内插图：仅静态时叠加真实图（动画期是纯位图，不参与）
                if (t == null && !showFrozen) {
                    val expandImage: (Any?) -> Unit = { expandedImage = it }
                    var imgY = 0f
                    for (i in fromRow until toRow) {
                        val line = lines[i]
                        if (i > fromRow && line.paragraph != lines[i - 1].paragraph) imgY += spacingPx
                        if (line.image) {
                            val paragraph = chapter.paragraphs[line.paragraph]
                            val model = imageModel(paragraph)
                            var retry by remember(model) { mutableIntStateOf(0) }
                            val y = imgY.roundToInt()
                            Box(Modifier.offset { IntOffset(0, y) }.size(width.dp, (line.height / densityValue).dp)
                                .clipToBounds()
                                .combinedClickable(onClick = tapNavigation::click,
                                    onLongClickLabel = "放大查看插图", onLongClick = { expandImage(model) }),
                                contentAlignment = Alignment.Center) {
                                val request = remember(context, model, retry) {
                                    ImageRequest.Builder(context).data(model).setParameter("readerRetry", retry).crossfade(false).build()
                                }
                                var failed by remember(model) { mutableStateOf(false) }
                                AsyncImage(request, "小说插图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                    onLoading = { failed = false }, onSuccess = { failed = false }, onError = { failed = true })
                                if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("插图暂时无法加载", color = foreground)
                                    AppTextButton(onClick = { retry++ }) { Text("重新加载插图", color = foreground) }
                                }
                            }
                        }
                        imgY += line.height
                    }
                }
            }
        }
    }
    expandedImage?.let { model -> IllustrationViewer(model) { expandedImage = null } }
}
