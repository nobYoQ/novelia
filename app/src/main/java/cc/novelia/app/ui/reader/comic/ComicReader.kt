@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import cc.novelia.app.data.model.ComicPreferences
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.reader.ComicPage
import cc.novelia.app.reader.ComicStripLayout
import cc.novelia.app.ui.theme.appReducedMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal val ComicReadingMode.label: String get() = when (this) {
    ComicReadingMode.RightToLeft -> "从右向左"
    ComicReadingMode.LeftToRight -> "从左向右"
    ComicReadingMode.Webtoon -> "纵向条漫"
}

@Composable
internal fun ComicReader(
    title: String, pages: List<ComicPage>, mode: ComicReadingMode, initialPage: Int, initialOffset: Int,
    preferences: ComicPreferences, sections: List<ComicSection>, defaultMode: ComicReadingMode,
    onDefaultMode: (ComicReadingMode) -> Unit, onPreferences: (ComicPreferences) -> Unit,
    onPosition: (Int, Int, Boolean) -> Unit, onBack: () -> Unit, onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var chapters by rememberSaveable { mutableStateOf(false) }
    var overview by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var stripZoom by rememberSaveable { mutableStateOf(false) }
    var anchorPage by rememberSaveable { mutableIntStateOf(initialPage) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(initialOffset.coerceIn(0, pages[initialPage].height - 1)) }
    val latestPosition by rememberUpdatedState(onPosition)
    val webtoon = mode == ComicReadingMode.Webtoon
    val rtl = mode == ComicReadingMode.RightToLeft
    val reduced = appReducedMotion()
    val layout = remember(pages) { ComicStripLayout(pages) }
    val frames = remember(pages, rtl, preferences.splitWide) {
        buildList {
            pages.forEachIndexed { index, p ->
                if (preferences.splitWide && p.text == null && p.width > p.height && p.width > 1) {
                    add(index to if (rtl) 1 else 0)
                    add(index to if (rtl) 0 else 1)
                } else add(index to -1)
            }
        }
    }
    val firstFrame = remember(frames) { IntArray(pages.size).also { starts ->
        for (i in frames.indices.reversed()) starts[frames[i].first] = i
    } }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)
        .testTag("comic-reader")) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        key(mode, preferences.splitWide) {
            val start = firstFrame[anchorPage]
            val pager = rememberPagerState(initialPage = start + if (frames[start].second >= 0 && anchorOffset == 1) 1 else 0) { frames.size }
            val stripWidth = if (webtoon && stripZoom) width * 2 else width
            val first = layout.stripAt(anchorPage, anchorOffset)
            val list = rememberLazyListState(first,
                ((anchorOffset - layout[first].top).coerceAtLeast(0) * stripWidth.toDouble() / pages[anchorPage].width).roundToInt())
            val scope = rememberCoroutineScope()
            val page by remember { derivedStateOf {
                if (webtoon) layout[list.firstVisibleItemIndex].page else frames[pager.currentPage].first
            } }
            val sectionIndex = remember(sections, page) {
                val hit = sections.binarySearchBy(page) { it.first }
                if (hit >= 0) hit else -hit - 2
            }
            val chapter = sections[sectionIndex]
            // 扫图 EPUB 经常每个 spine 只有一页，这时滑杆仍能定位整册。
            val progressRange = if (chapter.count == 1) pages.indices else chapter.first until chapter.first + chapter.count
            fun position(): ComicPosition? {
                if (webtoon) {
                    if (list.layoutInfo.visibleItemsInfo.isEmpty()) return null
                    val strip = layout[list.firstVisibleItemIndex]
                    val offset = (strip.top + list.firstVisibleItemScrollOffset * pages[strip.page].width.toDouble() / stripWidth)
                        .roundToInt().coerceIn(strip.top, strip.bottom - 1)
                    return ComicPosition(strip.page, offset, !list.canScrollForward)
                }
                return ComicPosition(page, pager.currentPage - firstFrame[page], pager.currentPage == frames.lastIndex)
            }
            fun save(position: ComicPosition?) {
                if (position == null) return
                anchorPage = position.page
                anchorOffset = position.offset
                latestPosition(position.page, position.offset, position.completed)
            }
            // 只在停稳或主动离开时写入书架，拖动长图不会逐像素写盘。
            LaunchedEffect(stripWidth) {
                snapshotFlow {
                    if (list.isScrollInProgress || pager.isScrollInProgress) null else position()
                }.collect(::save)
            }
            val saveCurrent by rememberUpdatedState { save(position()) }
            DisposableEffect(Unit) { onDispose { saveCurrent() } }

            fun seek(target: Int) {
                scope.launch {
                    if (webtoon) list.scrollToItem(layout.firstStrip[target])
                    else pager.scrollToPage(firstFrame[target])
                }
            }
            fun step(delta: Int) {
                if (webtoon) scope.launch { list.scrollBy(delta * list.layoutInfo.viewportSize.height * .9f) }
                else scope.launch {
                    val next = (pager.currentPage + delta).coerceIn(frames.indices)
                    if (reduced) pager.scrollToPage(next) else pager.animateScrollToPage(next)
                }
            }
            val draggingPage by pager.interactionSource.collectIsDraggedAsState()
            val draggingStrip by list.interactionSource.collectIsDraggedAsState()
            LaunchedEffect(draggingPage, draggingStrip) { if (draggingPage || draggingStrip) menu = false }
            if (webtoon) {
                val pan = rememberScrollState()
                var focusX by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(stripZoom, focusX) {
                    val x = focusX ?: return@LaunchedEffect
                    if (stripZoom) {
                        val max = snapshotFlow { pan.maxValue }.first { it in 1 until Int.MAX_VALUE }
                        pan.scrollTo(x.coerceIn(0, max))
                    }
                    focusX = null
                }
                // 双击围绕手指位置放大整条漫画，换页和保存进度仍使用原图坐标。
                val last = pages.last()
                val tail = (constraints.maxHeight - stripWidth * last.height.toDouble() / last.width).coerceAtLeast(0.0)
                val stripWidthDp = with(LocalDensity.current) { stripWidth.toDp() }
                Box(Modifier.fillMaxSize().horizontalScroll(pan, enabled = stripZoom)) {
                    LazyColumn(state = list, modifier = Modifier.width(stripWidthDp).fillMaxHeight().testTag("comic-strip").semantics {
                        stateDescription = if (stripZoom) "200%" else "100%"
                    },
                        contentPadding = PaddingValues(bottom = with(LocalDensity.current) { tail.toFloat().toDp() })) {
                        items(layout.size) { index ->
                            val strip = layout[index]
                            val source = pages[strip.page]
                            val imageModifier = Modifier.fillMaxWidth()
                                .aspectRatio(source.width.toFloat() / (strip.bottom - strip.top))
                                .pointerInput(index, stripZoom, width) {
                                    detectTapGestures(onTap = { menu = !menu }, onDoubleTap = { tap ->
                                        if (source.text != null) return@detectTapGestures
                                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return@detectTapGestures
                                        val factor = if (stripZoom) .5f else 2f
                                        val newOffset = (tap.y * factor - (item.offset + tap.y)).roundToInt()
                                        if (!stripZoom) focusX = tap.x.roundToInt()
                                        list.requestScrollToItem(index, newOffset)
                                        stripZoom = !stripZoom
                                    })
                                }
                            if (source.text != null) ComicTextPage(source.text, imageModifier)
                            else ResolvedPage(source, strip.page, imageModifier) { resolved -> ComicStripImage(resolved, strip, stripWidth, Modifier.fillMaxSize()) }
                        }
                    }
                }
            } else {
                HorizontalPager(pager, modifier = Modifier.fillMaxSize(),
                    reverseLayout = rtl != (LocalLayoutDirection.current == LayoutDirection.Rtl)) { index ->
                    val (physical, half) = frames[index]
                    val source = pages[physical]
                    val tap: (Float) -> Unit = { fraction ->
                        when {
                            fraction < .25f -> step(if (rtl) 1 else -1)
                            fraction > .75f -> step(if (rtl) -1 else 1)
                            else -> menu = !menu
                        }
                    }
                    if (source.text != null) ComicTextPage(source.text, Modifier.fillMaxSize().pointerInput(physical) {
                        detectTapGestures { tap(it.x / size.width.coerceAtLeast(1)) }
                    }) else ResolvedPage(source, physical, Modifier.fillMaxSize()) { resolved ->
                        ComicPagedImage(resolved, physical, preferences, half, tap)
                    }
                }
            }
            if (menu) {
                ComicHeader(title, chapter, onBack, onDetails, Modifier.align(Alignment.TopCenter))
                ComicControls(progressRange, page, mode, hasChapters = sections.size > 1, wholeBook = progressRange == pages.indices,
                    previousChapter = sections.getOrNull(sectionIndex - 1)?.let { section -> { seek(section.first) } },
                    nextChapter = sections.getOrNull(sectionIndex + 1)?.let { section -> { seek(section.first) } },
                    canBack = if (webtoon) list.canScrollBackward else pager.currentPage > 0,
                    canForward = if (webtoon) list.canScrollForward else pager.currentPage < frames.lastIndex,
                    step = ::step, seek = ::seek, onChapters = { chapters = true }, onPages = { overview = true },
                    onSettings = { settings = true }, onMode = { next ->
                        save(position())
                        onPreferences(preferences.copy(mode = next))
                    }, modifier = Modifier.align(Alignment.BottomCenter))
            } else ComicPageIndicator(page - progressRange.first + 1, progressRange.last - progressRange.first + 1,
                { menu = true }, Modifier.align(Alignment.BottomCenter))
            if (chapters) ComicChapterSheet(sections, sectionIndex, { seek(sections[it].first); chapters = false }, { chapters = false })
            if (overview) ComicOverview(pages, sections, page, { target -> seek(target); overview = false }, { overview = false })
        }
    }
    if (settings) ComicDisplaySettings(preferences, defaultMode, onDefaultMode, onPreferences,
        details = { settings = false; onDetails() }, close = { settings = false })

}

private data class ComicPosition(val page: Int, val offset: Int, val completed: Boolean)
