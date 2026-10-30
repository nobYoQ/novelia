package cc.novelia.app.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.webdav.WebDavProjection
import cc.novelia.app.reader.*
import cc.novelia.app.ui.markdown.textOffsetAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** 同一章内跨布局保留的恢复状态；不持有存储、导航或协程作用域。 */
internal class ReaderPositionState(position: Position?, paragraphs: List<ReadingParagraph>, settings: ReaderSettings) {
    var viewportWidth by mutableIntStateOf(0)
    var previousPagination by mutableStateOf(settings.staticPagination)
    var restoringAnchor by mutableStateOf(false)
    var initialAnchorRestored by mutableStateOf(false)
    var pendingScrollRestore by mutableStateOf<ReadingRestoreAnchor?>(null)
    var restoredScrollAnchor by mutableStateOf<RestoredScrollAnchor?>(null)
    var scrollAnchor by mutableStateOf(Triple((position?.index ?: 1) - 1, position?.textOffset ?: 0,
        paragraphs.getOrNull((position?.index ?: 1) - 1)?.index))
    var previousAnchorSource by mutableStateOf(WebDavProjection.anchorSource(settings))
    var previousFont by mutableStateOf<Any?>(null)
    var previousLayoutGeneration by mutableStateOf<Any?>(null)
}

internal class ReaderPositionLayout(
    val state: ReaderPositionState, val initialPosition: Position?, val paragraphs: List<ReadingParagraph>,
    val settings: ReaderSettings, val scroll: LazyListState, val eInk: EInkPageState,
    val layoutGeneration: Any, val scrollLayouts: SnapshotStateMap<Int, ParagraphScrollLayout>,
    val fontKey: Any,
) {
    fun scrollTextOffset(source: Int?): Int {
        if(scroll.firstVisibleItemIndex == 0) return 0
        val restored = state.restoredScrollAnchor
        // 两种渲染器可能把同一字符排到不同的行；在用户实际移动前，
        // 保留原请求字符，避免从起点略早于锚点的行切回另一模式时，
        // 阅读位置退回整整一页。
        if(restored != null && restored.layoutGeneration === layoutGeneration && restored.itemIndex == scroll.firstVisibleItemIndex && restored.pixelOffset == scroll.firstVisibleItemScrollOffset)
            return restored.textOffset
        return source?.let { scrollLayouts[it]?.textOffsetAt(scroll.firstVisibleItemScrollOffset) } ?: 0
    }
}

@Composable internal fun rememberReaderPositionLayout(
    position: Position?, paragraphs: List<ReadingParagraph>, settings: ReaderSettings,
    scroll: LazyListState, eInk: EInkPageState,
    fontKey: Any = settings.fontFamily to settings.customFont,
): ReaderPositionLayout {
    val state = remember { ReaderPositionState(position, paragraphs, settings) }
    val generation = remember(paragraphs, settings.fontSize, settings.lineHeight, settings.paragraphSpacing,
        settings.width, settings.indent, settings.parallel, settings.weight, settings.staticPagination, state.viewportWidth, fontKey,
        settings.horizontalMargin, settings.verticalMargin, settings.showWideToc,
        settings.showEpubRuby, settings.epubFootnotePopup, settings.showEpubStyles) { Any() }
    val layouts = remember(generation) { mutableStateMapOf<Int, ParagraphScrollLayout>() }
    return ReaderPositionLayout(state, position, paragraphs, settings, scroll, eInk, generation, layouts,
        listOf(fontKey, settings.resolvedHorizontalMargin, settings.resolvedVerticalMargin, settings.showWideToc))
}

/** 保留原有 effect keys、保存时机和字符锚点恢复顺序；页面提供最新的保存回调。 */
@Composable internal fun ReaderPositionEffects(
    layout: ReaderPositionLayout, ref: BookRef, chapterId: String,
    chapterProgress: Pair<Int, Int>?, onSavePosition: () -> Unit,
) {
    val state = layout.state
    val position = layout.initialPosition
    val paragraphs = layout.paragraphs
    val settings = layout.settings
    val scroll = layout.scroll
    val eInk = layout.eInk
    val layoutGeneration = layout.layoutGeneration
    val scrollLayouts = layout.scrollLayouts
    val lifecycleOwner = LocalLifecycleOwner.current
    // 局部函数引用按声明比较，rememberUpdatedState 可能因此保留
    // 捕获了上一阅读模式设置的旧引用。
    val latestSavePosition by rememberUpdatedState(onSavePosition)
    LaunchedEffect(chapterProgress) { if(chapterProgress != null) latestSavePosition() }
    DisposableEffect(lifecycleOwner, ref, chapterId) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) latestSavePosition() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); latestSavePosition() }
    }
    LaunchedEffect(scroll, chapterId, settings.staticPagination) {
        if(!settings.staticPagination) snapshotFlow {
            if(scroll.isScrollInProgress || !state.initialAnchorRestored || state.restoringAnchor) null else SettledReadingScroll(
                scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, scroll.canScrollForward,
                scroll.layoutInfo.totalItemsCount, scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index)
        }.distinctUntilChanged().collectLatest { settled ->
            if(settled != null) { delay(500); latestSavePosition() }
        }
    }
    // 滚动布局仍挂载时持续捕获锚点；切换模式后其测量数据会消失，
    // 不能等布局销毁后再尝试读取。
    LaunchedEffect(scroll, scrollLayouts, settings.staticPagination) {
        if(!settings.staticPagination) snapshotFlow {
            val index = (scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)
            val paragraph = paragraphs.getOrNull(index)
            val source = paragraph?.index
            // 重排时等待新行数据，不能用空布局把已经捕获的字符锚点清零。
            val waitingForText = paragraph != null && paragraph.imageUrl == null && paragraph.localImageId == null &&
                scrollLayouts[paragraph.index]?.parts?.size != paragraph.parts.size
            if(state.previousLayoutGeneration !== layoutGeneration || state.previousFont != null && state.previousFont != layout.fontKey || scroll.firstVisibleItemIndex > 0 && waitingForText) null
            else Triple(index, layout.scrollTextOffset(source), source)
        }.collect { if(it != null && !state.restoringAnchor && state.pendingScrollRestore == null) state.scrollAnchor = it }
    }
    // 模式切换使用字符锚点衔接，语言过滤变化时先按原始段落编号重新找投影下标。
    // LazyColumn 需要先挂载目标项、等待文字测量，再转换字符位置为可滚动的像素位置。
    LaunchedEffect(settings.staticPagination, layoutGeneration) {
        val changed = state.previousPagination != settings.staticPagination
        val sourceChanged = state.previousAnchorSource != WebDavProjection.anchorSource(settings)
        val fontChanged = state.previousFont != null && state.previousFont != layout.fontKey
        val layoutChanged = state.previousLayoutGeneration != null && state.previousLayoutGeneration !== layoutGeneration
        state.restoringAnchor = true
        try {
            if(settings.staticPagination && (changed || sourceChanged)) {
                val anchor = if(state.previousPagination) Triple(eInk.paragraph, eInk.textOffset, eInk.sourceIndex) else state.scrollAnchor
                eInk.find(anchor.first, if(sourceChanged) 0 else anchor.second, anchor.third)
                state.pendingScrollRestore = null
            } else if(!settings.staticPagination) {
                if(state.pendingScrollRestore == null) state.pendingScrollRestore = if(changed && eInk.pages.isNotEmpty()) ReadingRestoreAnchor(eInk.paragraph, eInk.sourceIndex, if(sourceChanged) 0 else eInk.textOffset)
                    else if(sourceChanged) ReadingRestoreAnchor(state.scrollAnchor.first, state.scrollAnchor.third, 0)
                    else if((fontChanged || layoutChanged) && scroll.firstVisibleItemIndex > 0) ReadingRestoreAnchor(state.scrollAnchor.first, state.scrollAnchor.third, state.scrollAnchor.second)
                    // 新组合可能来自横竖屏重建；旧像素偏移不再对应同一行，始终优先恢复字符锚点。
                    else position?.takeIf { !state.initialAnchorRestored && it.textOffset > 0 }?.let { ReadingRestoreAnchor((it.index - 1).coerceAtLeast(0), it.sourceParagraph, it.textOffset) }
                val target = state.pendingScrollRestore
                if(target != null) {
                    val index = (target.sourceIndex?.let { source -> paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 } }
                        ?: target.paragraph).coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0))
                    scroll.scrollToItem(index + 1)
                    val paragraph = paragraphs.getOrNull(index)
                    if(target.textOffset > 0 && paragraph != null && paragraph.imageUrl == null && paragraph.localImageId == null) {
                        val layout = snapshotFlow { scrollLayouts[paragraph.index] }.first { it != null && it.parts.size == paragraph.parts.size }!!
                        scroll.scrollToItem(index + 1, layout.scrollOffsetAt(target.textOffset))
                    }
                    if(scroll.firstVisibleItemIndex == index + 1) {
                        state.restoredScrollAnchor = RestoredScrollAnchor(layoutGeneration, index + 1, scroll.firstVisibleItemScrollOffset, target.textOffset)
                        state.scrollAnchor = Triple(index, target.textOffset, paragraph?.index)
                    }
                    state.pendingScrollRestore = null
                }
            }
            state.previousPagination = settings.staticPagination
            state.previousAnchorSource = WebDavProjection.anchorSource(settings)
            state.previousFont = layout.fontKey
            state.previousLayoutGeneration = layoutGeneration
            state.initialAnchorRestored = true
        } finally { state.restoringAnchor = false }
        latestSavePosition()
    }
    LaunchedEffect(eInk.pageIndex, eInk.pages, eInk.ready, settings.staticPagination) { if(settings.staticPagination) latestSavePosition() }
}

internal data class RestoredScrollAnchor(val layoutGeneration: Any, val itemIndex: Int, val pixelOffset: Int, val textOffset: Int)
internal data class ReadingRestoreAnchor(val paragraph: Int, val sourceIndex: Int?, val textOffset: Int,
    val anchorSource: String? = null, val anchorTextHash: String? = null)
private data class SettledReadingScroll(val index: Int, val offset: Int, val canScrollForward: Boolean, val itemCount: Int, val lastVisibleIndex: Int?)
