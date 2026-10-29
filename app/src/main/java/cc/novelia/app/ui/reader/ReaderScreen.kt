@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppIconButton
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.chapters.ChapterOffline
import cc.novelia.app.data.chapters.chapterFreshness
import cc.novelia.app.data.library.nextMountedVolume
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.markdown.textOffsetAt
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import cc.novelia.app.ui.theme.LocalScreenPageButtons
import cc.novelia.app.ui.theme.ReaderPageTheme
import cc.novelia.app.ui.theme.activityOrNull
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.readerColors
import cc.novelia.app.ui.notes.NoteEditorDialog
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString


/**
 * 阅读器入口：单书偏好优先于全局偏好，再把电子纸/减少动效约束传递到整棵阅读界面。
 * ReaderContent 负责加载和位置生命周期；正文投影、静态分页与搜索算法位于 reader 包。
 */
@Composable fun ReaderScreen(c: AppController, ref: BookRef, chapterId: String) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    val eInk = settings.eInkMode
    val appEInk = LocalEInkMode.current || eInk
    CompositionLocalProvider(LocalScreenPageButtons provides settings.showEInkScreenButtons) {
    AppInteractionMode(appEInk, LocalReducedMotion.current || eInk) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            ReaderContent(c, ref, chapterId, wide = maxWidth >= 840.dp)
        }
    }
    }
}

/**
 * 当前导航项拥有正文与弹层会话，目录数据跨章复用；跨章先准备正文，再保存位置并交接导航状态。
 * 滚动和静态分页共享段落/字符锚点，恢复、拖动预览或重新测量期间不提交中间位置。
 * 已读状态独立于屏顶位置保存，工具栏、搜索框和键盘以浮层叠加，不参与正文分页。
 */
@Composable private fun ReaderContent(c: AppController, ref: BookRef, chapterId: String, wide: Boolean) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    val readerFont = rememberReaderFont(settings)
    // 导航过渡期间，离开的阅读器仍可能处于组合中；此时完成的刷新
    // 不得消费新阅读器的搜索定位或章末跳转标记。
    val readerEntry = remember(ref, chapterId) { c.nav.currentBackStackEntry }
    var menu by rememberSaveable(ref.key) {
        mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerMenuVisible") ?: true)
    }
    var preferences by remember { mutableStateOf(false) }; var toc by remember { mutableStateOf(false) }; var search by remember { mutableStateOf(false) }; var query by rememberSaveable { mutableStateOf("") }; var version by remember { mutableIntStateOf(0) }
    var speechSheet by remember { mutableStateOf(false) }
    var editingTapZones by remember { mutableStateOf(false) }
    val preferenceState = rememberReaderPreferencesState(preferences)
    var tocQuery by rememberSaveable(ref.key) { mutableStateOf(readerEntry?.savedStateHandle?.remove<String>("readerTocQuery").orEmpty()) }
    var tocReversed by rememberSaveable(ref.key) { mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerTocReversed") ?: false) }
    var tocLocated by rememberSaveable(ref.key) { mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerTocLocated") ?: false) }
    var tocLocateRequest by remember { mutableIntStateOf(if(tocLocated) 0 else 1) }
    val tocScroll = rememberLazyListState(
        remember(ref, chapterId) { readerEntry?.savedStateHandle?.remove<Int>("readerTocIndex") ?: 0 },
        remember(ref, chapterId) { readerEntry?.savedStateHandle?.remove<Int>("readerTocOffset") ?: 0 }
    )
    LaunchedEffect(toc, wide) {
        if(toc && !wide) {
            // 由目录在数据就绪后统一定位，避免这里的顶部重置覆盖当前章定位。
            tocLocateRequest++
        }
    }
    var bookSearch by remember { mutableStateOf(false) }
    var returnPointJson by rememberSaveable(ref.key, chapterId) {
        mutableStateOf(readerEntry?.savedStateHandle?.remove<String>("readerReturnPoint"))
    }
    val returnPoint = remember(returnPointJson) { returnPointJson?.let { runCatching { appJson.decodeFromString<ReadingReturnPoint>(it) }.getOrNull() } }
    var refreshAnchor by remember(ref, chapterId) { mutableStateOf<ReadingRestoreAnchor?>(null) }
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val bookCacheGenerations by c.store.bookCacheGenerations.collectAsStateWithLifecycle()
    val bookCacheGeneration = bookCacheGenerations[ref.key] ?: 0L
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val tocState = remember(c, ref, profile, c.session.capture(), cacheGeneration) { c.readerToc(ref) }
    val enteredCacheGeneration = remember(ref, chapterId) { c.store.cacheGeneration.value }
    val enteredBookCacheGeneration = remember(ref, chapterId) { bookCacheGeneration }
    val readingLifecycle = LocalLifecycleOwner.current
    val speechStatus by ReadAloudService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current; val activity = context.activityOrNull()
    val colors = readerColors(settings, MaterialTheme.colorScheme)
    val reducedMotion = appReducedMotion()
    val focusManager = LocalFocusManager.current
    val background = colors.background
    val foreground = colors.foreground
    val toolbarBackground = colors.toolbar.copy(alpha = 1f - settings.resolvedToolbarTransparency)
    SideEffect { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply { isAppearanceLightStatusBars = background.luminance() > .5f; isAppearanceLightNavigationBars = background.luminance() > .5f } } }
    DisposableEffect(settings.keepScreenOn, settings.brightness) {
        val old = activity?.window?.attributes?.screenBrightness
        if(settings.keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = settings.brightness }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = old ?: -1f } }
    }
    BackHandler(preferences || (toc && !wide) || search || bookSearch) { preferences = false; toc = false; search = false; bookSearch = false }
    AsyncContent(listOf(ref, chapterId), load = { c.chapter(ref, chapterId, version > 0) }, refreshKey = version) { (chapter, cached), _ ->
        val knownChapterCount = maxOf(local.books.firstOrNull { it.book.ref == ref }?.book?.total ?: 0,
            local.updateSnapshots[ref.key]?.total ?: 0)
        LaunchedEffect(tocState, chapterId, knownChapterCount) {
            tocState.ensureLoaded(chapterId, knownChapterCount)
        }
        val chapterProgress = remember(tocState.toc, chapterId) {
            tocState.toc?.mapNotNull { it.chapterId }?.let { chapters ->
                chapters.indexOf(chapterId).takeIf { it >= 0 }?.let { it to chapters.size }
            }
        }
        // 只有内容和语言选择会改变投影；字号、段距等变化只触发后续重新排版。
        // 在计算线程完成繁体转换，避免滚动重组重复处理整章文本。
        var prepared by remember(ref, chapterId, chapter) { mutableStateOf<List<ReadingParagraph>?>(null) }
        LaunchedEffect(chapter, settings.mode, settings.engines, settings.parallel, settings.traditional) {
            prepared = withContext(Dispatchers.Default) {
                val jobContext = currentCoroutineContext()
                prepareReadingParagraphs(chapter, settings) { jobContext.ensureActive() }
            }
        }
        val paragraphs = prepared
        if(paragraphs == null) {
            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(!reducedMotion) CircularProgressIndicator(color = foreground)
                    Text("正在整理正文…", color = foreground)
                }
            }
            return@AsyncContent
        }
        val paragraphTextHashes = remember(paragraphs) { mutableMapOf<Int, String>() }
        fun paragraphHash(paragraph: ReadingParagraph?): String? = paragraph?.let {
            paragraphTextHashes.getOrPut(it.index) { it.readingTextHash() }
        }
        LaunchedEffect(ref, chapterId, chapter.nextId, settings.prefetchChapters, settings.prefetchWifiOnly, cacheGeneration, bookCacheGeneration, readingLifecycle) {
            if(!ref.isLocal && settings.prefetchChapters > 0 && cacheGeneration == enteredCacheGeneration && bookCacheGeneration == enteredBookCacheGeneration) readingLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(600)
                try { ChapterOffline(c.store, c.api, c.session).prefetch(ref, chapter.nextId, settings.prefetchChapters, settings.prefetchWifiOnly) }
                catch(e: CancellationException) { throw e }
                catch(_: Exception) { /* 预加载为可选操作，失败不得中断当前章节阅读。 */ }
            }
        }
        var cachedWrittenAt by remember(ref, chapterId, chapter, version) { mutableLongStateOf(Long.MAX_VALUE) }
        LaunchedEffect(ref, chapterId, chapter, version) { cachedWrittenAt = withContext(Dispatchers.IO) { chapterFreshness(c.store, ref, chapterId) } }
        val translationUpdate = local.bookUpdates[ref.key]?.takeIf { update -> cached && !ref.isLocal && cachedWrittenAt < update.latestTranslationAt(settings.engines) }
        key(ref, chapterId, chapter) {
        val searchArrival = remember(ref, chapterId) { readerEntry?.savedStateHandle?.remove<Int>("readerSearchParagraph") }
        val arrivalMatch = remember(ref, chapterId) {
            val state = readerEntry?.savedStateHandle
            val part = state?.remove<Int>("readerSearchPart") ?: 0
            val start = state?.remove<Int>("readerSearchStart") ?: 0
            val end = state?.remove<Int>("readerSearchEnd") ?: start
            searchArrival?.let { ReadingTextMatch(it, part, start, end) }
        }
        var activeMatch by remember { mutableStateOf(arrivalMatch) }
        var chapterMatches by remember { mutableStateOf<List<ReadingTextMatch>>(emptyList()) }
        var matchedQuery by remember { mutableStateOf("") }
        var searchedParagraphs by remember { mutableStateOf(paragraphs) }
        LaunchedEffect(paragraphs) {
            if(searchedParagraphs !== paragraphs) {
                activeMatch = null; chapterMatches = emptyList(); matchedQuery = ""
                searchedParagraphs = paragraphs
            }
        }
        val position = remember(ref, chapterId) {
            val anchor = refreshAnchor
            val noteSource = readerEntry?.savedStateHandle?.remove<Int>("readerNoteSourceIndex")
            val noteAnchor = noteSource?.let { readingSourceAnchor(chapter, it) }
            val returned = readerEntry?.savedStateHandle?.remove<String>("readerRestorePosition")
                ?.let { runCatching { appJson.decodeFromString<ReadingReturnPoint>(it).resolvedPosition(paragraphs, settings) }.getOrNull() }
            if(noteAnchor != null) Position(chapterId,
                paragraphs.indexOfFirst { it.index >= noteAnchor }
                    .takeIf { it >= 0 }?.plus(1) ?: paragraphs.size)
            else if(returned != null) returned
            else if(searchArrival != null) Position(chapterId, searchArrival.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) + 1,
                textOffset = paragraphs.getOrNull(searchArrival)?.let { arrivalMatch?.textOffset(it, settings) } ?: 0)
            else if(anchor != null) Position(chapterId, (anchor.sourceIndex?.let { source -> paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 } } ?: anchor.paragraph).coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) + 1,
                textOffset = anchor.textOffset, sourceParagraph = anchor.sourceIndex, anchorSource = anchor.anchorSource,
                anchorTextHash = anchor.anchorTextHash).resolvedReadingPosition(paragraphs, settings)
            else if(readerEntry?.savedStateHandle?.remove<Boolean>("readerStartAtEnd") == true) Position(chapterId, Int.MAX_VALUE)
            else local.positions[ref.key]?.takeIf { it.chapterId == chapterId }?.resolvedReadingPosition(paragraphs, settings)
        }
        val scroll = rememberLazyListState(position?.index ?: 0, position?.offset ?: 0); val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
        val eInkInteraction = LocalEInkMode.current
        val chapterPull = rememberReaderChapterOverscrollGesture()
        val chapterPullReturn = remember { Animatable(0f) }
        LaunchedEffect(chapterPull.offset, chapterPull.active, reducedMotion) {
            if(chapterPull.active || reducedMotion) chapterPullReturn.snapTo(chapterPull.offset)
            else chapterPullReturn.animateTo(chapterPull.offset, tween(AppMotion.Release))
        }
        // 触摸状态和动画偏好在当前帧立即生效，即使旧回弹尚未结束。
        val chapterPullOffset = if(eInkInteraction) 0f else if(chapterPull.active || reducedMotion) chapterPull.offset else chapterPullReturn.value
        val eInk = remember { EInkPageState(position) }
        val positionLayout = rememberReaderPositionLayout(position, paragraphs, settings, scroll, eInk, readerFont.typeface)
        val positionState = positionLayout.state
        val layoutGeneration = positionLayout.layoutGeneration
        val scrollLayouts = positionLayout.scrollLayouts
        val firstParagraph by remember(settings.staticPagination, eInk, scroll) { derivedStateOf {
            if(settings.staticPagination) eInk.paragraph else (scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)
        } }
        val seekIndex = remember(paragraphs, settings.indent, settings.parallel) { ChapterSeekIndex(paragraphs, settings) }
        var seekJob by remember { mutableStateOf<Job?>(null) }
        var seekGeneration by remember { mutableIntStateOf(0) }
        var seekTarget by remember { mutableStateOf<Float?>(null) }
        var seekFocused by remember { mutableStateOf(false) }
        val readingProgress by remember(scroll, eInk, settings.staticPagination, seekIndex, scrollLayouts) { derivedStateOf {
            if(settings.staticPagination) eInk.pageIndex.toFloat() / (eInk.pages.size - 1).coerceAtLeast(1)
            else if(!scroll.canScrollForward && scroll.canScrollBackward) 1f
            else if(scroll.firstVisibleItemIndex == 0) 0f
            else {
                val index = (scroll.firstVisibleItemIndex - 1).coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0))
                val offset = paragraphs.getOrNull(index)?.let { scrollLayouts[it.index]?.textOffsetAt(scroll.firstVisibleItemScrollOffset) } ?: 0
                seekIndex.fractionAt(index, offset)
            }
        } }
        DisposableEffect(layoutGeneration) {
            onDispose { seekGeneration++; seekJob?.cancel(); seekJob = null; seekTarget = null }
        }
        val hasFallback = remember(paragraphs) { paragraphs.any { it.fallback } }
        var selected by remember { mutableStateOf<ReadingParagraph?>(null) }
        var note by remember { mutableStateOf<Note?>(null) }
        var bookmarkFeedback by remember { mutableStateOf<Job?>(null) }
        fun saveBookmark(paragraph: ReadingParagraph, edit: Boolean = false) {
            val candidate = Note(UUID.randomUUID().toString(), ref.key, chapterId, paragraph.index,
                paragraph.parts.firstOrNull()?.text.orEmpty(), "",
                bookTitle = c.store.state.value.books.firstOrNull { it.book.ref == ref }?.book?.title
                    ?: chapter.novelTitleZh?.takeIf(String::isNotBlank) ?: chapter.novelTitleJp.orEmpty(), chapterTitle = chapter.title)
            c.store.update { state ->
                if(state.notes.any { it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraph.index })
                    if(edit) state else state.copy(notes = state.notes.map { if(it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraph.index) it.copy(bookmarked = true) else it })
                else state.copy(notes = state.notes + candidate)
            }
            val saved = c.store.state.value.notes.firstOrNull { it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraph.index } ?: return
            bookmarkFeedback?.cancel()
            if(edit) note = saved
            else bookmarkFeedback = scope.launch {
                if(c.snackbar.showSnackbar(if(saved.id == candidate.id) "书签已保存" else "此处已保存书签", actionLabel = "编辑笔记",
                        withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    note = c.store.state.value.notes.firstOrNull { it.id == saved.id }
                }
            }
        }
        var leaving by remember { mutableStateOf(false) }
        var nextVolumePrompt by remember { mutableStateOf(false) }
        var openingVolume by remember { mutableStateOf(false) }
        var volumeError by remember { mutableStateOf<String?>(null) }
        val nextVolume = remember(local.books, ref) { local.nextMountedVolume(ref) }
        var lastSavedPosition by remember { mutableStateOf<Position?>(null) }
        var finding by remember { mutableStateOf(false) }
        var searchJob by remember { mutableStateOf<Job?>(null) }
        var searchGeneration by remember { mutableIntStateOf(0) }
        DisposableEffect(layoutGeneration) {
            onDispose {
                // 语言、模式或字号变化后，搜索不得继续等待已废弃的布局几何。
                searchGeneration++; searchJob?.cancel(); searchJob = null; finding = false
            }
        }
        var topOverlayHeight by remember { mutableIntStateOf(0) }
        var bottomOverlayHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        // 两种模式都为启用的底栏预留固定空间，不随工具栏或按钮显隐变化。
        val readingStatusBarHeight = if(settings.showReadingStatusBar) with(density) { 16.sp.toDp() } + 12.dp else 0.dp
        val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        // 隐藏状态栏时正文延伸到顶部，避免屏幕缺口仍留出整条空白；
        // 保留横屏两侧和底部安全区，目录与引导控件仍避让全部系统区域。
        val readingInsets = if(settings.hideStatusBar) safeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else safeInsets
        val safeTop = readingInsets.getTop(density)
        // 恢复定位或重新分页期间的中间画面不能覆盖真实进度。两种模式统一保存正文下标 + 1，
        // 因为滚动列表的第 0 项是章标题；字符偏移支持重排，像素偏移用于恢复原滚动布局。
        fun savePosition() {
            if(leaving || seekTarget != null || !positionState.initialAnchorRestored || positionState.restoringAnchor || positionState.previousPagination != settings.staticPagination ||
                (if(settings.staticPagination) !eInk.ready || eInk.pages.isEmpty() else scroll.layoutInfo.totalItemsCount == 0 || scroll.layoutInfo.visibleItemsInfo.isEmpty())) return
            val visible = if(settings.staticPagination) Position(chapterId, eInk.paragraph + 1, 0, chapter.title, textOffset = eInk.textOffset)
                else {
                    val paragraph = paragraphs.getOrNull(scroll.firstVisibleItemIndex - 1)
                    val textOffset = positionLayout.scrollTextOffset(paragraph?.index)
                    Position(chapterId, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, chapter.title, textOffset = textOffset)
                }
            val saved = c.store.state.value.positions[ref.key]?.takeIf { it.chapterId == chapterId }
            // 末页可能从最后一段中间开始；应保留精确恢复锚点，
            // 另行记录读完状态，回看后再次到末页也遵循此规则。
            val chapterCompleted = saved?.chapterCompleted == true ||
                if(settings.staticPagination) !eInk.canGoForward else !scroll.canScrollForward
            val next = visible.copy(chapterIndex = chapterProgress?.first ?: saved?.chapterIndex,
                chapterCount = chapterProgress?.second ?: saved?.chapterCount,
                paragraphCount = paragraphs.size, chapterCompleted = chapterCompleted,
                sourceParagraph = paragraphs.getOrNull(visible.index - 1)?.index,
                anchorSource = WebDavProjection.anchorSource(settings),
                anchorTextHash = paragraphHash(paragraphs.getOrNull(visible.index - 1)))
            val previous = lastSavedPosition
            if(previous == null || previous.chapterId != next.chapterId || previous.index != next.index || previous.offset != next.offset || previous.textOffset != next.textOffset || previous.title != next.title ||
                previous.chapterIndex != next.chapterIndex || previous.chapterCount != next.chapterCount || previous.paragraphCount != next.paragraphCount || previous.chapterCompleted != next.chapterCompleted ||
                previous.sourceParagraph != next.sourceParagraph || previous.anchorSource != next.anchorSource || previous.anchorTextHash != next.anchorTextHash) {
                c.store.savePosition(ref, next, bookTitle = chapter.novelTitleZh?.takeIf(String::isNotBlank) ?: chapter.novelTitleJp.orEmpty())
                lastSavedPosition = next
            }
        }
        fun rememberReadingPlace() {
            if(leaving || positionState.restoringAnchor || !positionState.initialAnchorRestored || (settings.staticPagination && !eInk.ready)) return
            val paragraph = if(settings.staticPagination) eInk.paragraph else (scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)
            val source = if(!settings.staticPagination && scroll.firstVisibleItemIndex == 0) null else paragraphs.getOrNull(paragraph)?.index
            val current = ReadingReturnPoint(Position(chapterId,
                index = if(settings.staticPagination) paragraph + 1 else scroll.firstVisibleItemIndex,
                offset = if(settings.staticPagination) 0 else scroll.firstVisibleItemScrollOffset,
                title = chapter.title, textOffset = if(settings.staticPagination) eInk.textOffset else positionLayout.scrollTextOffset(source),
                sourceParagraph = source, anchorSource = WebDavProjection.anchorSource(settings),
                anchorTextHash = if(source == null) null else paragraphHash(paragraphs.getOrNull(paragraph))), source)
            returnPointJson = appJson.encodeToString(retainReadingReturnPoint(returnPoint, current))
        }
        val onChapterLoaded by rememberUpdatedState<(ReaderChapterTarget, AppController.ReaderHandoff) -> Unit>({ target, loaded ->
            if(!leaving && c.nav.currentBackStackEntry == readerEntry) {
            savePosition()
            c.readPreparedChapter(loaded)
            leaving = true
            c.nav.currentBackStackEntry?.savedStateHandle?.apply {
                // 切换章节会重建阅读器，需一并交接工具栏可见状态。
                set("readerMenuVisible", menu)
                set("readerTocQuery", tocQuery)
                set("readerTocReversed", tocReversed)
                set("readerTocIndex", tocScroll.firstVisibleItemIndex)
                set("readerTocOffset", tocScroll.firstVisibleItemScrollOffset)
                set("readerTocLocated", tocLocated)
                if(target.returnPoint != null) set("readerRestorePosition", appJson.encodeToString(target.returnPoint))
                else returnPointJson?.let { set("readerReturnPoint", it) }
                if(target.startAtEnd) set("readerStartAtEnd", true)
                target.searchMatch?.let { match ->
                    set("readerSearchParagraph", match.paragraph)
                    set("readerSearchPart", match.part)
                    set("readerSearchStart", match.start)
                    set("readerSearchEnd", match.end)
                }
            }
            }
        })
        val chapterLoad = remember(ref, chapterId, scope) {
            ReaderChapterLoad(scope, { id -> c.prepareReaderChapter(ref, id) }, { target, loaded -> onChapterLoaded(target, loaded) }, { it.friendlyMessage() })
        }
        cc.novelia.app.ui.components.base.NetworkRecoveryEffect(chapterLoad.failure, chapterLoad::retry)
        val reconnecting = cc.novelia.app.ui.components.base.networkReconnecting()
        var inlineChapterLoad by remember { mutableStateOf(false) }
        DisposableEffect(chapterLoad) { onDispose { chapterLoad.cancel() } }
        val panelsClosed = !preferences && !editingTapZones && !search && (!toc || wide) && !speechSheet && !bookSearch && !nextVolumePrompt && selected == null && note == null
        val showTapTutorial = settings.tapPageTurn && !local.readerTapTutorialSeen && panelsClosed && !leaving &&
            !chapterLoad.loading && chapterLoad.error == null && positionState.initialAnchorRestored && !positionState.restoringAnchor && seekTarget == null &&
            (!settings.staticPagination || eInk.ready)
        cc.novelia.app.ui.toolbar.ReportEdgeToolbarReaderState(readerEntry?.id, menu,
            blocked = !panelsClosed || showTapTutorial || leaving || !positionState.initialAnchorRestored,
            top = if(menu) topOverlayHeight else 0, bottom = bottomOverlayHeight)
        val volumeKeysActive = panelsClosed && !showTapTutorial
        LaunchedEffect(volumeKeysActive) { if(volumeKeysActive) runCatching { focus.requestFocus() } }
        fun openChapter(id: String, startAtEnd: Boolean = false, match: ReadingTextMatch? = null, inline: Boolean = false,
            restore: ReadingReturnPoint? = null) {
            if(leaving || id == chapterId) return
            seekGeneration++; seekJob?.cancel(); seekTarget = null
            inlineChapterLoad = inline
            chapterLoad.request(ReaderChapterTarget(id, startAtEnd, match, restore))
        }
        fun returnToReadingPlace() {
            val target = returnPoint ?: return
            searchGeneration++; searchJob?.cancel(); finding = false; activeMatch = null
            search = false; bookSearch = false; toc = false; focusManager.clearFocus()
            if(target.position.chapterId != chapterId) {
                openChapter(target.position.chapterId, restore = target)
                return
            }
            chapterLoad.cancel()
            seekGeneration++; seekJob?.cancel(); seekTarget = null
            searchJob = scope.launch {
                positionState.restoringAnchor = true
                try {
                    val resolved = target.resolvedPosition(paragraphs, settings)
                    val paragraphIndex = (resolved.index - 1).coerceAtLeast(0)
                    if(settings.staticPagination) eInk.find(paragraphIndex, resolved.textOffset, target.sourceIndex)
                    else {
                        scroll.scrollToItem(resolved.index, resolved.offset)
                        val paragraph = paragraphs.getOrNull(paragraphIndex)
                        if(resolved.index > 0 && resolved.textOffset > 0 && paragraph != null && paragraph.imageUrl == null && paragraph.localImageId == null) {
                            val layout = snapshotFlow { scrollLayouts[paragraph.index] }.first { it != null && it.parts.size == paragraph.parts.size }!!
                            scroll.scrollToItem(resolved.index, layout.scrollOffsetAt(resolved.textOffset))
                        }
                        positionState.restoredScrollAnchor = RestoredScrollAnchor(layoutGeneration, resolved.index, scroll.firstVisibleItemScrollOffset, resolved.textOffset)
                    }
                    returnPointJson = null
                } finally { positionState.restoringAnchor = false }
                savePosition()
            }
        }
        fun refreshChapter() {
            seekGeneration++; seekJob?.cancel(); seekTarget = null
            chapterLoad.cancel()
            refreshAnchor = if(settings.staticPagination) ReadingRestoreAnchor(eInk.paragraph, eInk.sourceIndex, eInk.textOffset,
                WebDavProjection.anchorSource(settings), paragraphHash(paragraphs.getOrNull(eInk.paragraph)))
                else ReadingRestoreAnchor(firstParagraph, paragraphs.getOrNull(firstParagraph)?.index,
                    positionLayout.scrollTextOffset(paragraphs.getOrNull(firstParagraph)?.index), WebDavProjection.anchorSource(settings),
                    paragraphHash(paragraphs.getOrNull(firstParagraph)))
            savePosition()
            version++
            scope.launch { tocState.ensureLoaded(chapterId, knownChapterCount, refresh = true) }
        }
        fun openNextVolume() {
            val target = nextVolume ?: return
            if(openingVolume || leaving) return
            openingVolume = true
            volumeError = null
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    val id = withContext(Dispatchers.IO) {
                        val chapters = c.store.documentIndex(target.book.ref.id).chapters
                        chapters.firstOrNull { it.id == local.positions[target.book.ref.key]?.chapterId }?.id
                            ?: chapters.firstOrNull()?.id ?: error("下一分卷没有可阅读的章节")
                    }
                    savePosition(); leaving = true
                    c.nav.popBackStack(); c.read(target.book.ref, id)
                    c.nav.currentBackStackEntry?.savedStateHandle?.set("readerMenuVisible", menu)
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { leaving = false; volumeError = e.friendlyMessage() }
                finally { openingVolume = false }
            }
        }
        fun page(direction: Int) {
            if(chapterLoad.loading || leaving) return
            seekGeneration++; seekJob?.cancel(); seekTarget = null
            if(settings.staticPagination) {
                if(!eInk.ready) return
                if(direction > 0 && !eInk.canGoForward && eInk.pages.isNotEmpty()) { if(chapter.nextId != null) openChapter(chapter.nextId) else if(nextVolume != null) nextVolumePrompt = true }
                else if(direction < 0 && !eInk.canGoBack && eInk.pages.isNotEmpty()) chapter.prevId?.let { id ->
                    // 从章首向前翻页时，落在上一章末页。
                    openChapter(id, startAtEnd = true)
                }
                else { eInk.move(direction); savePosition() }
                return
            }
            val distance = scroll.layoutInfo.viewportSize.height.coerceAtLeast(1) * .85f
            scope.launch(Dispatchers.Main.immediate) { if(reducedMotion) scroll.scrollBy(distance * direction) else scroll.animateScrollBy(distance * direction, tween(AppMotion.Standard)) }
        }
        fun seekChapter(progress: Float) {
            if(leaving || chapterLoad.loading || positionState.restoringAnchor || !positionState.initialAnchorRestored ||
                (settings.staticPagination && !eInk.ready)) return
            val generation = ++seekGeneration
            seekJob?.cancel()
            chapterPull.cancel()
            searchGeneration++; searchJob?.cancel(); finding = false; activeMatch = null
            positionState.restoredScrollAnchor = null
            val target = progress.safeFraction()
            seekTarget = target
            seekJob = scope.launch {
                try {
                    if(settings.staticPagination) eInk.move(chapterSeekPage(target, eInk.pages.size) - eInk.pageIndex)
                    else scroll.seekChapter(target, seekIndex, paragraphs, scrollLayouts, animate = !reducedMotion)
                } finally {
                    // 已取消的预览不得覆盖新一轮拖动的待定位目标。
                    if(seekGeneration == generation) { seekTarget = null; savePosition() }
                }
            }
        }
        suspend fun revealMatch(match: ReadingTextMatch) = withContext(Dispatchers.Main.immediate) {
            val paragraph = paragraphs.getOrNull(match.paragraph) ?: return@withContext
            val textOffset = match.textOffset(paragraph, settings)
            activeMatch = match
            if(settings.staticPagination) { eInk.find(match.paragraph, textOffset); savePosition() }
            else {
                // 先挂载惰性列表项，再使用该语言片段已测量的行几何定位。
                scroll.scrollToItem(match.paragraph + 1)
                val layout = snapshotFlow { scrollLayouts[paragraph.index] }
                    .first { it != null && it.parts.size == paragraph.parts.size }!!
                val overlay = if(menu) (topOverlayHeight - safeTop).coerceAtLeast(0) else 0
                scroll.scrollToItem(match.paragraph + 1, layout.scrollOffsetAt(textOffset) - overlay)
                savePosition()
            }
        }
        fun findNext(direction: Int = 1) {
            if(query.isBlank() || finding) return
            val term = query.trim()
            focusManager.clearFocus()
            finding = true
            val generation = ++searchGeneration
            searchJob = scope.launch(Dispatchers.Main.immediate) {
                try {
                    val matches = if(matchedQuery == term) chapterMatches else withContext(Dispatchers.Default) {
                        val jobContext = currentCoroutineContext()
                        findReadingTextMatches(paragraphs, term) { jobContext.ensureActive() }
                    }
                    if(query.trim() != term) return@launch
                    chapterMatches = matches; matchedQuery = term
                    if(matches.isEmpty()) { activeMatch = null; c.message("没有找到匹配文字") }
                    else {
                        val offset = if(settings.staticPagination) eInk.textOffset else positionLayout.scrollTextOffset(paragraphs.getOrNull(firstParagraph)?.index)
                        val next = nextReadingMatchIndex(matches, activeMatch, direction, firstParagraph, offset) { match -> match.textOffset(paragraphs[match.paragraph], settings) }
                        rememberReadingPlace()
                        revealMatch(matches[next])
                    }
                } finally { if(generation == searchGeneration) finding = false }
            }
        }
        BackHandler(panelsClosed && !showTapTutorial) {
            if(chapterLoad.loading || chapterLoad.error != null) chapterLoad.cancel()
            else if(!leaving) { seekGeneration++; seekJob?.cancel(); seekTarget = null; savePosition(); leaving = true; c.back() }
        }
        ReaderPositionEffects(positionLayout, ref, chapterId, chapterProgress) { savePosition() }
        var arrivalApplied by remember { mutableStateOf(false) }
        LaunchedEffect(arrivalMatch, layoutGeneration) {
            if(arrivalMatch != null && !arrivalApplied) {
                snapshotFlow { positionState.initialAnchorRestored && !positionState.restoringAnchor }.first { it }
                if(menu) snapshotFlow { topOverlayHeight }.first { it > 0 }
                revealMatch(arrivalMatch)
                arrivalApplied = true
            }
        }
        // 云端历史只记录章节，精确段落/字符位置仍保存在本机；同步失败不阻断当前阅读。
        LaunchedEffect(chapterId) {
            if(c.session.profile.value != null && !ref.isLocal && !local.historyPaused) {
                try { c.cloudMutation("PUT", "user/read-history/${ref.key}", chapterId, "text/plain") }
                catch(e: CancellationException) { throw e }
                catch(_: Exception) { /* 历史同步失败时，本地阅读进度仍然可用。 */ }
            }
        }
        // 阅读器专用颜色仅作用于正文页面，所有设置弹层继承应用主题。
        ReaderPageTheme(settings.resolvedTheme == "monochrome") {
        Row(Modifier.fillMaxSize().background(background).testTag(if(wide) "reader-wide-layout" else "reader-compact-layout")) {
        if(wide) {
            Surface(Modifier.width(292.dp).fillMaxHeight().windowInsetsPadding(safeInsets), color = MaterialTheme.colorScheme.surface) {
                ReaderTocPane(c, ref, tocState, { scope.launch { tocState.ensureLoaded(chapterId, knownChapterCount, refresh = true) } }, chapterId, tocScroll, tocQuery, { tocQuery = it }, tocReversed, { tocReversed = it }, tocLocateRequest, { tocLocateRequest = 0; tocLocated = true }, { if(it != chapterId) rememberReadingPlace(); openChapter(it) })
            }
            VerticalDivider(Modifier.fillMaxHeight())
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(background).focusRequester(focus).onPreviewKeyEvent { event ->
            val direction = readerKeyDirection(event.nativeKeyEvent.keyCode, settings.volumeKeys)
            if(volumeKeysActive && !seekFocused && direction != 0) {
                if(event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) page(direction)
                true
            } else false
        }.focusable()) {
            Box(Modifier.matchParentSize().semantics { if(showTapTutorial || editingTapZones) hideFromAccessibility() }) {
            // 两种模式均使用固定正文视口；工具栏是同级浮层，
            // 不得给正文布局增加内边距或尺寸约束。
            val bodyInputEnabled = volumeKeysActive && !leaving && !chapterLoad.loading && !positionState.restoringAnchor && positionState.initialAnchorRestored && seekTarget == null
            val openToc: () -> Unit = { if(wide) { tocQuery = ""; tocLocateRequest++ } else toc = true }
            val tapNavigation = rememberReaderTapNavigation(settings.tapPageTurn, bodyInputEnabled, { menu = !menu }, { page(it) }, settings.resolvedTapZoneMap, openToc)
            if(settings.staticPagination) EInkPage(paragraphs, settings, eInk,
                Modifier.testTag("reader-page").align(Alignment.TopCenter).fillMaxHeight().windowInsetsPadding(readingInsets)
                    .padding(bottom = readingStatusBarHeight)
                    .widthIn(max = settings.width.dp).fillMaxWidth(),
                imageModel = { it.imageUrl ?: it.localImageId?.takeIf { ref.isLocal }?.let { id -> c.store.documentImage(ref.id, id) } },
                onToggleMenu = { menu = !menu }, onSelect = { selected = it }, onPage = { page(it) },
                background = background, foreground = foreground, activeMatch = activeMatch,
                interactionEnabled = bodyInputEnabled, contentPadding = PaddingValues(horizontal = settings.resolvedHorizontalMargin.dp, vertical = settings.resolvedVerticalMargin.dp), onNavigate = openToc)
            else LazyColumn(state = scroll, overscrollEffect = null, modifier = Modifier.testTag("reader-scroll").align(Alignment.TopCenter).fillMaxHeight()
                .windowInsetsPadding(readingInsets).padding(bottom = readingStatusBarHeight).widthIn(max = settings.width.dp).fillMaxWidth()
                .onSizeChanged { size ->
                    if(positionState.viewportWidth != 0 && positionState.viewportWidth != size.width)
                        positionState.pendingScrollRestore = ReadingRestoreAnchor(positionState.scrollAnchor.first, positionState.scrollAnchor.third, positionState.scrollAnchor.second)
                    positionState.viewportWidth = size.width
                }
                .clipToBounds()
                .readerTapFeedback(tapNavigation, foreground, settings.eInkMode || eInkInteraction)
                .readerTapNavigation(tapNavigation)
                .readerChapterOverscroll(scroll, chapterPull, chapter.nextId != null && !leaving && !chapterLoad.loading && seekTarget == null && !positionState.restoringAnchor && volumeKeysActive) {
                    chapter.nextId?.let { openChapter(it, inline = true) }
                }
                .graphicsLayer { translationY = -chapterPullOffset }
                , contentPadding = PaddingValues(horizontal = settings.resolvedHorizontalMargin.dp, vertical = settings.resolvedVerticalMargin.dp), verticalArrangement = Arrangement.spacedBy(settings.resolvedParagraphSpacing.dp)) {
                item("title", contentType = "title") {
                    Column(Modifier.fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "显示或收起阅读工具栏", onClick = tapNavigation::click)) {
                        Text(chapter.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = foreground, fontFamily = readerFont.family)
                        Spacer(Modifier.height(12.dp))
                        Text(if(ref.isLocal) "本地小说" else (providers[ref.provider].orEmpty() + " · " + if(settings.mode == "jp") "日文原文" else "机翻阅读"), style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .65f))
                        if(hasFallback) Text("部分段落暂无所选译文，显示原文。", style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .7f))
                    }
                }
                itemsIndexed(paragraphs, key = { _, p -> "paragraph-${p.index}" }, contentType = { _, p -> if(p.imageUrl != null || p.localImageId != null) "image" else "paragraph" }) { paragraphIndex, paragraph ->
                    DisposableEffect(paragraph.index, scrollLayouts) {
                        onDispose { scrollLayouts.remove(paragraph.index) }
                    }
                    val image = remember(ref, paragraph.imageUrl, paragraph.localImageId) {
                        paragraph.imageUrl ?: paragraph.localImageId?.takeIf { ref.isLocal }?.let { c.store.documentImage(ref.id, it) }
                    }
                    if(image != null) ReaderIllustration(image, foreground, tapNavigation::click)
                    else ReaderTextParagraph(paragraph, settings, layoutGeneration, foreground, tapNavigation::click, { selected = paragraph }, activeMatch?.takeIf { it.paragraph == paragraphIndex }, fontFamily = readerFont.family) { part, lines ->
                        val existing = scrollLayouts[paragraph.index] ?: ParagraphScrollLayout()
                        if(existing.parts[part] != lines) scrollLayouts[paragraph.index] = existing.copy(parts = existing.parts + (part to lines))
                    }
                }
                item("end", contentType = "footer") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(Modifier.padding(vertical = 24.dp)); Text("本章完", color = foreground.copy(alpha = .65f)); Spacer(Modifier.height(20.dp))
                        if(chapter.nextId != null) ReaderChapterPullHint(chapterPull.progress, chapterPull.active, chapterPull.ready,
                            inlineChapterLoad && chapterLoad.loading, foreground, Modifier.padding(bottom = 8.dp))
                        if(settings.showScrollPageButtons) {
                            if(chapter.nextId != null) AppButton(onClick = { openChapter(chapter.nextId, inline = true) }, modifier = Modifier.heightIn(min = 48.dp), enabled = !leaving && !chapterLoad.loading) { Text("阅读下一章") }
                            else if(nextVolume != null) { Text("下一分卷：${nextVolume.book.title}", color = foreground, modifier = Modifier.padding(bottom = 12.dp)); AppButton(onClick = { nextVolumePrompt = true }, modifier = Modifier.heightIn(min = 48.dp), enabled = !leaving) { Text("阅读下一分卷") } }
                            else AppOutlinedButton(onClick = { if(wide) { tocQuery = ""; tocLocateRequest++ } else toc = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回目录") }
                        }
                        // 章末留白让同一提示句位于控件上方，
                        // 不会改变正文视口高度或使章节重新分页。
                        Spacer(Modifier.height(with(density) { (bottomOverlayHeight - readingInsets.getBottom(density)).coerceAtLeast(0).toDp() }
                            .minus(readingStatusBarHeight).coerceAtLeast(0.dp)))
                    }
                }
            }
            ReaderTopToolbar(
                state = ReaderTopToolbarState(chapter.title, ref.isLocal, leaving, translationUpdate != null,
                    returnPoint != null, !leaving && !chapterLoad.loading && !positionState.restoringAnchor && positionState.initialAnchorRestored, speechStatus),
                search = ReaderChapterSearchState(search, query, finding, chapterMatches.indexOf(activeMatch), chapterMatches.size),
                menu = menu, reducedMotion = reducedMotion, toolbarBackground = toolbarBackground, foreground = foreground,
                modifier = Modifier.align(Alignment.TopCenter), onHeightChanged = { topOverlayHeight = it },
                onBack = { if(!leaving) { seekGeneration++; seekJob?.cancel(); seekTarget = null; chapterLoad.cancel(); savePosition(); leaving = true; c.back() } },
                onRefresh = { refreshChapter() }, onToggleSearch = { if(search) focusManager.clearFocus(); search = !search },
                onSpeech = { speechSheet = true }, onReturnToReading = { returnToReadingPlace() },
                onQueryChange = { searchGeneration++; searchJob?.cancel(); finding = false; query = it; activeMatch = null; chapterMatches = emptyList(); matchedQuery = "" },
                onFind = { findNext(it) }, onBookSearch = { focusManager.clearFocus(); bookSearch = true },
            )
            ReaderBottomToolbar(
                state = ReaderBottomToolbarState(
                    previousPageEnabled = if(settings.staticPagination) eInk.ready && (eInk.canGoBack || (eInk.pages.isNotEmpty() && chapter.prevId != null)) else scroll.canScrollBackward,
                    nextPageEnabled = if(settings.staticPagination) eInk.ready && (eInk.canGoForward || (eInk.pages.isNotEmpty() && (chapter.nextId != null || nextVolume != null))) else scroll.canScrollForward,
                    seekEnabled = !leaving && !chapterLoad.loading && !positionState.restoringAnchor && positionState.initialAnchorRestored &&
                        (if(settings.staticPagination) eInk.ready && eInk.pages.size > 1 else paragraphs.isNotEmpty() && (scroll.canScrollForward || scroll.canScrollBackward)),
                    previousChapterEnabled = chapter.prevId != null && !leaving && !chapterLoad.loading,
                    nextChapterEnabled = (chapter.nextId != null || nextVolume != null) && !leaving && !chapterLoad.loading,
                    nextIsVolume = chapter.nextId == null && nextVolume != null,
                    bookmarkEnabled = paragraphs.isNotEmpty(),
                    bookmarked = local.notes.any { it.bookmarked && it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraphs.getOrNull(firstParagraph)?.index },
                    progress = seekTarget ?: readingProgress, pageReady = eInk.ready, pageCount = eInk.pages.size,
                    pageIndex = eInk.pageIndex, chapterTitle = chapter.title, wide = wide, cached = cached,
                ),
                settings = settings, menu = menu, reducedMotion = reducedMotion, toolbarBackground = toolbarBackground,
                foreground = foreground, readingStatusBarHeight = readingStatusBarHeight, modifier = Modifier.align(Alignment.BottomCenter),
                onHeightChanged = { bottomOverlayHeight = it }, onPage = { page(it) }, onSeek = { seekChapter(it) },
                onSeekFocusChanged = { seekFocused = it }, onPreviousChapter = { chapter.prevId?.let { openChapter(it) } },
                onNextChapter = { if(chapter.nextId != null) openChapter(chapter.nextId) else nextVolumePrompt = true },
                onToc = { if(wide) { tocQuery = ""; tocLocateRequest++ } else toc = true },
                onBookmark = { paragraphs.getOrNull(firstParagraph)?.let { saveBookmark(it) } }, onPreferences = { preferences = true },
            )
            if((chapterLoad.loading && (!inlineChapterLoad || settings.staticPagination)) || chapterLoad.error != null) Surface(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(readingInsets)
                    .padding(bottom = with(density) { (bottomOverlayHeight - readingInsets.getBottom(density)).coerceAtLeast(0).toDp() })
                    .padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("reader-chapter-load-status"),
                shape = MaterialTheme.shapes.medium, color = toolbarBackground.copy(alpha = 1f), contentColor = foreground,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(if(chapterLoad.loading) { if(reconnecting) "正在重新连接…" else "正在加载章节…" } else "章节加载失败，仍在当前章", style = MaterialTheme.typography.titleSmall)
                    chapterLoad.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(!chapterLoad.loading) AppTextButton(onClick = chapterLoad::retry) { Text("重试加载章节") }
                        AppTextButton(onClick = chapterLoad::cancel) { Text(if(chapterLoad.loading) "取消加载" else "继续阅读") }
                    }
                }
            }
            }
            if(showTapTutorial) ReaderTapTutorial(settings.staticPagination, settings.eInkMode || eInkInteraction,
                readingInsets, settings.width, readingStatusBarHeight, Modifier.matchParentSize(), map = settings.resolvedTapZoneMap) {
                c.store.update { it.copy(readerTapTutorialSeen = true) }
            }
            if(editingTapZones) ReaderTapZoneEditor(settings.resolvedTapZoneMap, onSave = { map ->
                c.store.update { current ->
                    val value = (current.bookSettings[ref.key] ?: current.reader).copy(tapZoneMap = ReaderTapZoneMap.encode(map))
                    if(current.bookSettings.containsKey(ref.key)) current.copy(bookSettings = current.bookSettings + (ref.key to value))
                    else current.copy(reader = value)
                }
                editingTapZones = false
            }, onDismiss = { editingTapZones = false },
                modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight().windowInsetsPadding(readingInsets)
                    .padding(bottom = readingStatusBarHeight).widthIn(max = settings.width.dp).fillMaxWidth(),
                staticPagination = settings.staticPagination, eInk = settings.eInkMode || eInkInteraction)
        }
        }
        }
        if(nextVolumePrompt && nextVolume != null) AppAlertDialog(onDismissRequest = { nextVolumePrompt = false }, title = { Text("本卷已读完") }, text = { Column { Text("按书架中的分卷顺序接续：${nextVolume.book.title}"); volumeError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { AppTextButton(onClick = { openNextVolume() }, enabled = !openingVolume) { Text(if(openingVolume) "正在打开…" else "阅读下一分卷") } }, dismissButton = { AppTextButton(onClick = { nextVolumePrompt = false }) { Text("稍后") } })
        if(bookSearch) ReaderSheet(onDismissRequest = { bookSearch = false }) {
            BookSearchPanel(c, ref, chapterId, chapter, settings) { match ->
                rememberReadingPlace()
                bookSearch = false; search = false; focusManager.clearFocus()
                if(match.chapterId == chapterId) {
                    searchGeneration++; searchJob?.cancel(); finding = false
                    searchJob = scope.launch {
                        revealMatch(match.readingMatch)
                    }
                } else {
                    openChapter(match.chapterId, match = match.readingMatch)
                }
            }
        }
        if(toc && !wide) ReaderSheet(onDismissRequest = { toc = false }) {
            ReaderTocPane(c, ref, tocState, { scope.launch { tocState.ensureLoaded(chapterId, knownChapterCount, refresh = true) } }, chapterId, tocScroll, tocQuery, { tocQuery = it }, tocReversed, { tocReversed = it }, tocLocateRequest, { tocLocateRequest = 0; tocLocated = true },
                { id -> if(id != chapterId) rememberReadingPlace(); toc = false; openChapter(id) }, Modifier.fillMaxHeight(.8f))
        }
        if(speechSheet) ReaderSpeechSheet(settings, speechStatus, onDismiss = { speechSheet = false },
            onStart = {
                val first = firstParagraph
                val originalIndex = paragraphs.getOrNull(first)?.index ?: 0
                scope.launch {
                    try {
                        val jobContext = currentCoroutineContext()
                        ReadAloudService.start(context, chapter.title, settings, ref, chapterId, chapter.nextId) {
                            speechParagraphs(chapter, settings, originalIndex) { jobContext.ensureActive() }
                        }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { c.message(e.friendlyMessage()) }
                }
            },
            onPause = { context.startService(Intent(context, ReadAloudService::class.java).setAction("pause")) },
            onResume = { context.startService(Intent(context, ReadAloudService::class.java).setAction("resume")) },
            onStop = { context.startService(Intent(context, ReadAloudService::class.java).setAction("stop")) },
            onSettings = { speechSheet = false; preferences = true },
        )
        selected?.let { paragraph -> ReaderParagraphSheet(paragraph, onDismiss = { selected = null },
            onShare = { c.share(paragraph.parts.joinToString("\n\n") { it.text }); selected = null },
            onBookmark = { saveBookmark(paragraph); selected = null },
            onEditNote = { saveBookmark(paragraph, edit = true); selected = null },
        ) }
        note?.let { bookmark -> NoteEditorDialog(bookmark, { note = null }) { text ->
            c.store.update { it.copy(notes = it.notes.map { existing -> if(existing.id == bookmark.id) existing.copy(text = text) else existing }.filter { it.bookmarked || it.text.isNotBlank() }) }
            bookmarkFeedback?.cancel()
            bookmarkFeedback = scope.launch { c.snackbar.showSnackbar("笔记已保存") }
        } }
        }
    }
    if(preferences) {
        ReaderPreferencesSheet(onDismissRequest = { preferences = false }) { expanded, onExpandedChange ->
            ReaderPreferences(settings, local.bookSettings.containsKey(ref.key), { perBook -> c.store.update { it.copy(bookSettings = if(perBook) it.bookSettings + (ref.key to settings) else it.bookSettings - ref.key) } },
                state = preferenceState, modifier = Modifier.fillMaxSize(), livePreview = true, defaultSettings = local.reader,
                onEditTapZones = { preferences = false; editingTapZones = true },
                onShowTapTutorial = { preferences = false; c.store.update { it.copy(readerTapTutorialSeen = false) } }, headerActions = {
                    AppIconButton(onClick = { onExpandedChange(!expanded) }) {
                        ReaderSheetExpandIcon(expanded)
                    }
                    AppTextButton(onClick = { preferences = false }) { Text("关闭面板") }
                }) { value -> c.store.update { if(it.bookSettings.containsKey(ref.key)) it.copy(bookSettings = it.bookSettings + (ref.key to value)) else it.copy(reader = value) } }
        }
    }
}
