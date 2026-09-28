@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.chapters.ChapterOffline
import cc.novelia.app.data.chapters.chapterFreshness
import cc.novelia.app.data.library.nextMountedVolume
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.appVerticalScroll
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.feedback.StickerAccent
import cc.novelia.app.ui.markdown.textOffsetAt
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import cc.novelia.app.ui.theme.ReaderPageTheme
import cc.novelia.app.ui.theme.activityOrNull
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.readerColors
import cc.novelia.app.ui.notes.NoteEditorDialog
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读器入口：单书偏好优先于全局偏好，再把电子纸/减少动效约束传递到整棵阅读界面。
 * ReaderContent 负责加载和位置生命周期；正文投影、静态分页与搜索算法位于 reader 包。
 */
@Composable fun ReaderScreen(c: AppController, ref: BookRef, chapterId: String) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    val eInk = settings.eInkMode
    val appEInk = LocalEInkMode.current || eInk
    AppInteractionMode(appEInk, LocalReducedMotion.current || eInk) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            ReaderContent(c, ref, chapterId, wide = maxWidth >= 840.dp)
        }
    }
}

@Composable private fun ReaderContent(c: AppController, ref: BookRef, chapterId: String, wide: Boolean) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    // Outgoing readers can remain composed during a navigation transition. A refresh
    // completing then must not consume the incoming reader's search/end-of-chapter flags.
    val readerEntry = remember(ref, chapterId) { c.nav.currentBackStackEntry }
    var menu by rememberSaveable(ref.key) {
        mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerMenuVisible") ?: true)
    }
    var preferences by remember { mutableStateOf(false) }; var toc by remember { mutableStateOf(false) }; var search by remember { mutableStateOf(false) }; var query by rememberSaveable { mutableStateOf("") }; var version by remember { mutableIntStateOf(0) }
    var speechSheet by remember { mutableStateOf(false) }
    val preferenceState = rememberReaderPreferencesState()
    var tocQuery by rememberSaveable(ref.key) { mutableStateOf(readerEntry?.savedStateHandle?.remove<String>("readerTocQuery").orEmpty()) }
    var tocReversed by rememberSaveable(ref.key) { mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerTocReversed") ?: false) }
    var tocLocated by rememberSaveable(ref.key) { mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerTocLocated") ?: false) }
    var tocLocateRequest by remember { mutableIntStateOf(if(tocLocated) 0 else 1) }
    val tocScroll = rememberLazyListState(
        remember(ref, chapterId) { readerEntry?.savedStateHandle?.remove<Int>("readerTocIndex") ?: 0 },
        remember(ref, chapterId) { readerEntry?.savedStateHandle?.remove<Int>("readerTocOffset") ?: 0 }
    )
    var bookSearch by remember { mutableStateOf(false) }
    var refreshAnchor by remember(ref, chapterId) { mutableStateOf<ReadingRestoreAnchor?>(null) }
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val enteredCacheGeneration = remember(ref, chapterId) { c.store.cacheGeneration.value }
    val readingLifecycle = LocalLifecycleOwner.current
    val speechStatus by ReadAloudService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current; val activity = context.activityOrNull()
    val colors = readerColors(settings.resolvedTheme, MaterialTheme.colorScheme)
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
        var chapterProgress by remember(ref, chapterId) { mutableStateOf<Pair<Int, Int>?>(null) }
        LaunchedEffect(ref, chapterId) {
            chapterProgress = withContext(Dispatchers.IO) {
                try {
                    val ids = if(ref.isLocal) c.store.documentIndex(ref.id).chapters.map { it.id }
                    else {
                        // Reading progress must not start an extra request or block chapter loading.
                        val account = c.session.capture().account ?: "guest"
                        c.metadataCache.read(hashName("$account:novel/${ref.key}"))
                            ?.let { appJson.decodeFromString<WebDetail>(it).toc.mapNotNull { item -> item.chapterId } }
                    }
                    ids?.let { chapters -> chapters.indexOf(chapterId).takeIf { it >= 0 }?.let { it to chapters.size } }
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { null }
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
        LaunchedEffect(ref, chapterId, chapter.nextId, settings.prefetchChapters, settings.prefetchWifiOnly, cacheGeneration, readingLifecycle) {
            if(!ref.isLocal && settings.prefetchChapters > 0 && cacheGeneration == enteredCacheGeneration) readingLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(600)
                try { ChapterOffline(c.store, c.api, c.session).prefetch(ref, chapter.nextId, settings.prefetchChapters, settings.prefetchWifiOnly) }
                catch(e: CancellationException) { throw e }
                catch(_: Exception) { /* Preloading is optional and never interrupts the foreground chapter. */ }
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
            if(searchArrival != null) Position(chapterId, searchArrival.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) + 1,
                textOffset = paragraphs.getOrNull(searchArrival)?.let { arrivalMatch?.textOffset(it, settings) } ?: 0)
            else if(anchor != null) Position(chapterId, (anchor.sourceIndex?.let { source -> paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 } } ?: anchor.paragraph).coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) + 1, textOffset = anchor.textOffset)
            else if(readerEntry?.savedStateHandle?.remove<Boolean>("readerStartAtEnd") == true) Position(chapterId, Int.MAX_VALUE)
            else local.positions[ref.key]?.takeIf { it.chapterId == chapterId }
        }
        val scroll = rememberLazyListState(position?.index ?: 0, position?.offset ?: 0); val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
        val chapterPull = rememberReaderChapterOverscrollGesture()
        val chapterPullReturn = remember { Animatable(0f) }
        LaunchedEffect(chapterPull.offset, chapterPull.active, reducedMotion) {
            if(chapterPull.active || reducedMotion) chapterPullReturn.snapTo(chapterPull.offset)
            else chapterPullReturn.animateTo(chapterPull.offset, tween(AppMotion.Release))
        }
        // Touch and the motion preference take effect in this frame, even during an old return.
        val chapterPullOffset = if(chapterPull.active || reducedMotion) chapterPull.offset else chapterPullReturn.value
        val eInk = remember { EInkPageState(position) }
        var readerViewportWidth by remember { mutableIntStateOf(0) }
        val layoutGeneration = remember(paragraphs, settings.fontSize, settings.lineHeight, settings.paragraphSpacing, settings.width, settings.indent, settings.parallel, settings.weight, settings.staticPagination, readerViewportWidth) { Any() }
        val scrollLayouts = remember(layoutGeneration) { mutableStateMapOf<Int, ParagraphScrollLayout>() }
        var previousPagination by remember { mutableStateOf(settings.staticPagination) }
        var restoringAnchor by remember { mutableStateOf(false) }
        var initialAnchorRestored by remember { mutableStateOf(false) }
        var pendingScrollRestore by remember { mutableStateOf<ReadingRestoreAnchor?>(null) }
        var restoredScrollAnchor by remember { mutableStateOf<RestoredScrollAnchor?>(null) }
        fun scrollTextOffset(source: Int?): Int {
            if(scroll.firstVisibleItemIndex == 0) return 0
            val restored = restoredScrollAnchor
            // The two renderers may wrap a character onto different lines. Preserve the
            // requested character until the reader actually moves, avoiding a whole-page
            // retreat when switching back from a line that starts just before that anchor.
            if(restored != null && restored.layoutGeneration === layoutGeneration && restored.itemIndex == scroll.firstVisibleItemIndex && restored.pixelOffset == scroll.firstVisibleItemScrollOffset)
                return restored.textOffset
            return source?.let { scrollLayouts[it]?.textOffsetAt(scroll.firstVisibleItemScrollOffset) } ?: 0
        }
        val firstParagraph by remember(settings.staticPagination, eInk, scroll) { derivedStateOf {
            if(settings.staticPagination) eInk.paragraph else (scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)
        } }
        val percent by remember(scroll, paragraphs.size) { derivedStateOf { (((scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0).toFloat() / (paragraphs.size + 1).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100) } }
        val hasFallback = remember(paragraphs) { paragraphs.any { it.fallback } }
        var selected by remember { mutableStateOf<ReadingParagraph?>(null) }
        var note by remember { mutableStateOf<Note?>(null) }
        var bookmarkFeedback by remember { mutableStateOf<Job?>(null) }
        fun saveBookmark(paragraph: ReadingParagraph, edit: Boolean = false) {
            val candidate = Note(UUID.randomUUID().toString(), ref.key, chapterId, paragraph.index,
                paragraph.parts.firstOrNull()?.text.orEmpty(), "",
                bookTitle = c.store.state.value.books.firstOrNull { it.book.ref == ref }?.book?.title.orEmpty(), chapterTitle = chapter.title)
            c.store.update { state ->
                if(state.notes.any { it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraph.index }) state
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
                // A search must never wait on geometry discarded by language/mode/font changes.
                searchGeneration++; searchJob?.cancel(); searchJob = null; finding = false
            }
        }
        var topOverlayHeight by remember { mutableIntStateOf(0) }
        var bottomOverlayHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        // Reserve a stable footer in paged mode, independent of toolbar/button visibility.
        val pageProgressHeight = with(density) { 16.sp.toDp() } + 12.dp
        // Search and its keyboard are overlays too; only system bars/cutouts bound the reading viewport.
        val readingInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val safeTop = readingInsets.getTop(density)
        val volumeKeysActive = !preferences && !search && (!toc || wide) && !speechSheet && !bookSearch && !nextVolumePrompt && selected == null && note == null
        LaunchedEffect(volumeKeysActive) { if(volumeKeysActive) runCatching { focus.requestFocus() } }
        // 恢复定位或重新分页期间的中间画面不能覆盖真实进度。两种模式统一保存正文下标 + 1，
        // 因为滚动列表的第 0 项是章标题；字符偏移支持重排，像素偏移用于恢复原滚动布局。
        fun savePosition() {
            if(leaving || !initialAnchorRestored || restoringAnchor || previousPagination != settings.staticPagination ||
                (if(settings.staticPagination) !eInk.ready || eInk.pages.isEmpty() else scroll.layoutInfo.totalItemsCount == 0 || scroll.layoutInfo.visibleItemsInfo.isEmpty()) ||
                c.store.state.value.historyPaused) return
            val visible = if(settings.staticPagination) Position(chapterId, eInk.paragraph + 1, 0, chapter.title, textOffset = eInk.textOffset)
                else {
                    val paragraph = paragraphs.getOrNull(scroll.firstVisibleItemIndex - 1)
                    val textOffset = scrollTextOffset(paragraph?.index)
                    Position(chapterId, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, chapter.title, textOffset = textOffset)
                }
            val saved = c.store.state.value.positions[ref.key]?.takeIf { it.chapterId == chapterId }
            // A final page can begin halfway through the last paragraph. Keep that exact
            // restore anchor while recording completion separately, including after rereading.
            val chapterCompleted = saved?.chapterCompleted == true ||
                if(settings.staticPagination) !eInk.canGoForward else !scroll.canScrollForward
            val next = visible.copy(chapterIndex = chapterProgress?.first ?: saved?.chapterIndex,
                chapterCount = chapterProgress?.second ?: saved?.chapterCount,
                paragraphCount = paragraphs.size, chapterCompleted = chapterCompleted)
            val previous = lastSavedPosition
            if(previous == null || previous.chapterId != next.chapterId || previous.index != next.index || previous.offset != next.offset || previous.textOffset != next.textOffset || previous.title != next.title ||
                previous.chapterIndex != next.chapterIndex || previous.chapterCount != next.chapterCount || previous.paragraphCount != next.paragraphCount || previous.chapterCompleted != next.chapterCompleted) {
                c.store.savePosition(ref, next)
                lastSavedPosition = next
            }
        }
        val onChapterLoaded by rememberUpdatedState<(ReaderChapterTarget, AppController.ReaderHandoff) -> Unit>({ target, loaded ->
            if(!leaving && c.nav.currentBackStackEntry == readerEntry) {
            savePosition()
            c.readPreparedChapter(loaded)
            leaving = true
            c.nav.currentBackStackEntry?.savedStateHandle?.apply {
                // Chapter navigation recreates the reader; carry its toolbar state forward.
                set("readerMenuVisible", menu)
                set("readerTocQuery", tocQuery)
                set("readerTocReversed", tocReversed)
                set("readerTocIndex", tocScroll.firstVisibleItemIndex)
                set("readerTocOffset", tocScroll.firstVisibleItemScrollOffset)
                set("readerTocLocated", tocLocated)
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
        DisposableEffect(chapterLoad) { onDispose { chapterLoad.cancel() } }
        fun openChapter(id: String, startAtEnd: Boolean = false, match: ReadingTextMatch? = null) {
            if(leaving || id == chapterId) return
            chapterLoad.request(ReaderChapterTarget(id, startAtEnd, match))
        }
        fun refreshChapter() {
            chapterLoad.cancel()
            refreshAnchor = if(settings.staticPagination) ReadingRestoreAnchor(eInk.paragraph, eInk.sourceIndex, eInk.textOffset)
                else ReadingRestoreAnchor(firstParagraph, paragraphs.getOrNull(firstParagraph)?.index, scrollTextOffset(paragraphs.getOrNull(firstParagraph)?.index))
            savePosition()
            version++
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
            if(settings.staticPagination) {
                if(!eInk.ready) return
                if(direction > 0 && !eInk.canGoForward && eInk.pages.isNotEmpty()) { if(chapter.nextId != null) openChapter(chapter.nextId) else if(nextVolume != null) nextVolumePrompt = true }
                else if(direction < 0 && !eInk.canGoBack && eInk.pages.isNotEmpty()) chapter.prevId?.let { id ->
                    // A previous-page turn lands at the end of the preceding chapter.
                    openChapter(id, startAtEnd = true)
                }
                else { eInk.move(direction); savePosition() }
                return
            }
            val distance = scroll.layoutInfo.viewportSize.height.coerceAtLeast(1) * .85f
            scope.launch(Dispatchers.Main.immediate) { if(reducedMotion) scroll.scrollBy(distance * direction) else scroll.animateScrollBy(distance * direction, tween(AppMotion.Standard)) }
        }
        suspend fun revealMatch(match: ReadingTextMatch) = withContext(Dispatchers.Main.immediate) {
            val paragraph = paragraphs.getOrNull(match.paragraph) ?: return@withContext
            val textOffset = match.textOffset(paragraph, settings)
            activeMatch = match
            if(settings.staticPagination) { eInk.find(match.paragraph, textOffset); savePosition() }
            else {
                // First mount the lazy item, then use measured line geometry within its language part.
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
                        val offset = if(settings.staticPagination) eInk.textOffset else scrollTextOffset(paragraphs.getOrNull(firstParagraph)?.index)
                        val next = nextReadingMatchIndex(matches, activeMatch, direction, firstParagraph, offset) { match -> match.textOffset(paragraphs[match.paragraph], settings) }
                        revealMatch(matches[next])
                    }
                } finally { if(generation == searchGeneration) finding = false }
            }
        }
        BackHandler(!preferences && (!toc || wide) && !search && !speechSheet && !bookSearch && !nextVolumePrompt && selected == null && note == null) {
            if(chapterLoad.loading || chapterLoad.error != null) chapterLoad.cancel()
            else if(!leaving) { savePosition(); leaving = true; c.back() }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        // Local callable references compare by declaration, so rememberUpdatedState can
        // retain a reference whose captured settings belong to the previous reading mode.
        val latestSavePosition by rememberUpdatedState<() -> Unit>({ savePosition() })
        LaunchedEffect(chapterProgress) { if(chapterProgress != null) latestSavePosition() }
        DisposableEffect(lifecycleOwner, ref, chapterId) {
            val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) latestSavePosition() }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer); latestSavePosition() }
        }
        LaunchedEffect(scroll, chapterId, local.historyPaused, settings.staticPagination) {
            if(!local.historyPaused && !settings.staticPagination) snapshotFlow {
                if(scroll.isScrollInProgress || !initialAnchorRestored || restoringAnchor) null else SettledReadingScroll(
                    scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, scroll.canScrollForward,
                    scroll.layoutInfo.totalItemsCount, scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index)
            }.distinctUntilChanged().collectLatest { settled ->
                if(settled != null) { delay(500); latestSavePosition() }
            }
        }
        // Capture continuously while the scrolling layout is still mounted. Its geometry
        // disappears on a mode switch, so do not try to read it after disposal.
        var scrollAnchor by remember { mutableStateOf(Triple((position?.index ?: 1) - 1, position?.textOffset ?: 0, paragraphs.getOrNull((position?.index ?: 1) - 1)?.index)) }
        LaunchedEffect(scroll, scrollLayouts, settings.staticPagination) {
            if(!settings.staticPagination) snapshotFlow {
                val index = (scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)
                val source = paragraphs.getOrNull(index)?.index
                Triple(index, scrollTextOffset(source), source)
            }.collect { if(!restoringAnchor) scrollAnchor = it }
        }
        // 模式切换使用字符锚点衔接，语言过滤变化时先按原始段落编号重新找投影下标。
        // LazyColumn 需要先挂载目标项、等待文字测量，再转换字符位置为可滚动的像素位置。
        LaunchedEffect(settings.staticPagination, layoutGeneration) {
            val changed = previousPagination != settings.staticPagination
            restoringAnchor = true
            try {
                if(settings.staticPagination && changed) {
                    eInk.find(scrollAnchor.first, scrollAnchor.second, scrollAnchor.third)
                    pendingScrollRestore = null
                } else if(!settings.staticPagination) {
                    if(pendingScrollRestore == null) pendingScrollRestore = if(changed && eInk.pages.isNotEmpty()) ReadingRestoreAnchor(eInk.paragraph, eInk.sourceIndex, eInk.textOffset)
                        else position?.takeIf { !initialAnchorRestored && it.textOffset > 0 && it.offset == 0 }?.let { ReadingRestoreAnchor((it.index - 1).coerceAtLeast(0), null, it.textOffset) }
                    val target = pendingScrollRestore
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
                            restoredScrollAnchor = RestoredScrollAnchor(layoutGeneration, index + 1, scroll.firstVisibleItemScrollOffset, target.textOffset)
                            scrollAnchor = Triple(index, target.textOffset, paragraph?.index)
                        }
                        pendingScrollRestore = null
                    }
                }
                previousPagination = settings.staticPagination
                initialAnchorRestored = true
            } finally { restoringAnchor = false }
            latestSavePosition()
        }
        LaunchedEffect(eInk.pageIndex, eInk.pages, eInk.ready, settings.staticPagination) { if(settings.staticPagination) latestSavePosition() }
        var arrivalApplied by remember { mutableStateOf(false) }
        LaunchedEffect(arrivalMatch, layoutGeneration) {
            if(arrivalMatch != null && !arrivalApplied) {
                snapshotFlow { initialAnchorRestored && !restoringAnchor }.first { it }
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
                catch(_: Exception) { /* Local progress remains available when history sync fails. */ }
            }
        }
        // Keep reader-specific colors inside the page; all settings sheets inherit the app theme.
        ReaderPageTheme(settings.resolvedTheme == "monochrome") {
        Row(Modifier.fillMaxSize().background(background).testTag(if(wide) "reader-wide-layout" else "reader-compact-layout")) {
        if(wide) {
            Surface(Modifier.width(292.dp).fillMaxHeight().windowInsetsPadding(readingInsets), color = MaterialTheme.colorScheme.surface) {
                ReaderTocPane(c, ref, chapterId, tocScroll, tocQuery, { tocQuery = it }, tocReversed, { tocReversed = it }, tocLocateRequest, { tocLocateRequest = 0; tocLocated = true }, { openChapter(it) })
            }
            VerticalDivider(Modifier.fillMaxHeight())
        }
        Box(Modifier.weight(1f).fillMaxHeight().background(background).focusRequester(focus).onPreviewKeyEvent { event ->
            val direction = readerKeyDirection(event.nativeKeyEvent.keyCode, settings.volumeKeys)
            if(volumeKeysActive && direction != 0) {
                if(event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) page(direction)
                true
            } else false
        }.focusable()) {
            // Both modes have a fixed viewport. Toolbars are sibling overlays and must
            // never contribute padding or constraints to the text's layout.
            if(settings.staticPagination) EInkPage(paragraphs, settings, eInk,
                Modifier.testTag("reader-page").align(Alignment.TopCenter).fillMaxHeight().windowInsetsPadding(readingInsets)
                    .padding(bottom = pageProgressHeight)
                    .widthIn(max = settings.width.dp).fillMaxWidth().padding(horizontal = 24.dp)
                    .padding(vertical = 16.dp),
                imageModel = { it.imageUrl ?: it.localImageId?.takeIf { ref.isLocal }?.let { id -> c.store.documentImage(ref.id, id) } },
                onToggleMenu = { menu = !menu }, onSelect = { selected = it }, onPage = { page(it) },
                background = background, foreground = foreground, activeMatch = activeMatch)
            else LazyColumn(state = scroll, overscrollEffect = null, modifier = Modifier.testTag("reader-scroll").align(Alignment.TopCenter).fillMaxHeight()
                .windowInsetsPadding(readingInsets).widthIn(max = settings.width.dp).fillMaxWidth()
                .onSizeChanged { size ->
                    if(readerViewportWidth != 0 && readerViewportWidth != size.width)
                        pendingScrollRestore = ReadingRestoreAnchor(scrollAnchor.first, scrollAnchor.third, scrollAnchor.second)
                    readerViewportWidth = size.width
                }
                .clipToBounds()
                .readerChapterOverscroll(scroll, chapterPull, chapter.nextId != null && !leaving && !chapterLoad.loading && !restoringAnchor && volumeKeysActive) {
                    chapter.nextId?.let { openChapter(it) }
                }
                .graphicsLayer { translationY = -chapterPullOffset }
                , contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(settings.resolvedParagraphSpacing.dp)) {
                item("title", contentType = "title") {
                    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "显示或收起阅读工具栏") { menu = !menu }) {
                        Text(chapter.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = foreground)
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
                    if(image != null) ReaderIllustration(image, foreground) { menu = !menu }
                    else ReaderTextParagraph(paragraph, settings, layoutGeneration, foreground, { menu = !menu }, { selected = paragraph }, activeMatch?.takeIf { it.paragraph == paragraphIndex }) { part, lines ->
                        val existing = scrollLayouts[paragraph.index] ?: ParagraphScrollLayout()
                        if(existing.parts[part] != lines) scrollLayouts[paragraph.index] = existing.copy(parts = existing.parts + (part to lines))
                    }
                }
                item("end", contentType = "footer") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(Modifier.padding(vertical = 24.dp)); Text("本章完", color = foreground.copy(alpha = .65f)); Spacer(Modifier.height(20.dp))
                        if(chapter.nextId != null) Text("上拉加载下一章", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .65f))
                        if(settings.showScrollPageButtons) {
                            if(chapter.nextId != null) Button(onClick = { openChapter(chapter.nextId) }, modifier = Modifier.heightIn(min = 48.dp), enabled = !leaving && !chapterLoad.loading) { Text("阅读下一章") }
                            else if(nextVolume != null) { Text("下一分卷：${nextVolume.book.title}", color = foreground, modifier = Modifier.padding(bottom = 12.dp)); Button(onClick = { nextVolumePrompt = true }, modifier = Modifier.heightIn(min = 48.dp), enabled = !leaving) { Text("阅读下一分卷") } }
                            else OutlinedButton(onClick = { if(wide) { tocQuery = ""; tocLocateRequest++ } else toc = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回目录") }
                        }
                    }
                }
            }
            if(!settings.staticPagination && chapter.nextId != null && chapterPullOffset > 0f) {
                // Keep the feedback visible above the toolbar, without changing the reading viewport.
                Column(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(readingInsets)
                    .padding(bottom = with(density) { (bottomOverlayHeight - readingInsets.getBottom(density)).coerceAtLeast(0).toDp() })
                    .widthIn(max = settings.width.dp).fillMaxWidth()
                    .height(with(density) { chapterPullOffset.toDp() }).clipToBounds().background(background)
                    .testTag("reader-next-chapter-pull"), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(if(chapterPull.ready) "松手加载下一章" else "继续上拉加载下一章", color = foreground,
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { chapterPull.progress }, modifier = Modifier.width(160.dp).height(3.dp),
                        color = foreground, trackColor = foreground.copy(alpha = .15f))
                }
            }
            ReaderOverlayVisibility(menu, Modifier.align(Alignment.TopCenter), enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + slideInVertically(tween(AppMotion.Standard)) { -it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + slideOutVertically(tween(AppMotion.Release)) { -it }) {
                Surface(Modifier.testTag("reader-top-toolbar"), color = toolbarBackground, contentColor = foreground) {
                // This height is used only to place search results below the overlay.
                Column(Modifier.onSizeChanged { topOverlayHeight = it.height }) {
                    TopAppBar(title = { Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
                        IconButton(onClick = { if(!leaving) { chapterLoad.cancel(); savePosition(); leaving = true; c.back() } }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    }, actions = {
                        if(!ref.isLocal) IconButton(onClick = { refreshChapter() }, enabled = !leaving) { Icon(Icons.Outlined.Refresh, "刷新本章译文") }
                        IconButton(onClick = { if(search) focusManager.clearFocus(); search = !search }) { Icon(Icons.Outlined.Search, "搜索本章") }
                        IconButton(onClick = { preferences = true }) { Icon(Icons.Outlined.TextFields, "阅读设置") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent, titleContentColor = foreground, actionIconContentColor = foreground, navigationIconContentColor = foreground))
                    if(translationUpdate != null) TextButton(onClick = { refreshChapter() }, enabled = !leaving, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("本书有新的译文，可刷新本章") }
                    AnimatedVisibility(search,
                        enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + expandVertically(tween(AppMotion.Standard), expandFrom = Alignment.Top),
                        exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + shrinkVertically(tween(AppMotion.Release), shrinkTowards = Alignment.Top)
                    ) {
                        Column {
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(query, { searchGeneration++; searchJob?.cancel(); finding = false; query = it; activeMatch = null; chapterMatches = emptyList(); matchedQuery = "" }, label = { Text("搜索本章段落") }, singleLine = true, modifier = Modifier.weight(1f), enabled = search,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = foreground, unfocusedTextColor = foreground,
                                    focusedBorderColor = foreground, unfocusedBorderColor = foreground.copy(alpha = .5f),
                                    focusedLabelColor = foreground, unfocusedLabelColor = foreground.copy(alpha = .7f), cursorColor = foreground))
                            TextButton(onClick = { findNext() }, enabled = search && query.isNotBlank() && !finding,
                                colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f))) { Text(if(finding) "查找中" else "查找") }
                        }
                        if(chapterMatches.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${(chapterMatches.indexOf(activeMatch) + 1).coerceAtLeast(0)} / ${chapterMatches.size}${if(chapterMatches.size == 2000) "+" else ""}", style = MaterialTheme.typography.labelMedium, color = foreground)
                            TextButton(onClick = { findNext(-1) }, enabled = !finding, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("上一处") }
                            TextButton(onClick = { findNext(1) }, enabled = !finding, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("下一处") }
                        }
                        TextButton(onClick = { focusManager.clearFocus(); bookSearch = true }, Modifier.padding(horizontal = 12.dp), colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("整本搜索（本地 / 已缓存章节）") }
                        }
                    }
                }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).testTag("reader-bottom-toolbar").onSizeChanged { bottomOverlayHeight = it.height }) {
                val persistentControls = settings.showPageButtons || settings.staticPagination
                if(settings.showPageButtons) Row(Modifier.fillMaxWidth().background(toolbarBackground).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { page(-1) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f)), enabled = if(settings.staticPagination) eInk.ready && (eInk.canGoBack || (eInk.pages.isNotEmpty() && chapter.prevId != null)) else scroll.canScrollBackward) { Text(if(settings.staticPagination) "上一页" else "上一屏") }
                    OutlinedButton(onClick = { page(1) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f)), enabled = if(settings.staticPagination) eInk.ready && (eInk.canGoForward || (eInk.pages.isNotEmpty() && (chapter.nextId != null || nextVolume != null))) else scroll.canScrollForward) { Text(if(settings.staticPagination) "下一页" else "下一屏") }
                }
                ReaderOverlayVisibility(menu, Modifier, enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + slideInVertically(tween(AppMotion.Standard)) { it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + slideOutVertically(tween(AppMotion.Release)) { it }) {
                    Surface(color = toolbarBackground, contentColor = foreground) {
                        Column(if(persistentControls) Modifier else Modifier.navigationBarsPadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { chapter.prevId?.let { openChapter(it) } }, enabled = chapter.prevId != null && !leaving && !chapterLoad.loading) { Icon(Icons.Outlined.SkipPrevious, "上一章") }
                                TextButton(onClick = { if(wide) { tocQuery = ""; tocLocateRequest++ } else toc = true }, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(18.dp)); Text(if(wide) " 定位目录" else " 目录") }
                                val bookmarked = local.notes.any { it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraphs.getOrNull(firstParagraph)?.index }
                                IconButton(onClick = { paragraphs.getOrNull(firstParagraph)?.let { saveBookmark(it) } }, enabled = paragraphs.isNotEmpty(), modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                                    Icon(if(bookmarked) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, if(bookmarked) "已保存书签" else "保存书签")
                                }
                                IconButton(onClick = { speechSheet = true }) {
                                    if(speechStatus == ReadAloudService.SLEEP_TIMER_FINISHED) StickerAccent(MidoriSticker.Sleep, speechStatus, Modifier.size(40.dp).semantics { contentDescription = "朗读定时已结束，打开朗读设置" })
                                    else Icon(Icons.Outlined.VolumeUp, "朗读本章")
                                }
                                IconButton(onClick = { if(chapter.nextId != null) openChapter(chapter.nextId) else nextVolumePrompt = true }, enabled = (chapter.nextId != null || nextVolume != null) && !leaving && !chapterLoad.loading) { Icon(Icons.Outlined.SkipNext, if(chapter.nextId == null && nextVolume != null) "下一分卷" else "下一章") }
                            }
                            Text(if(settings.staticPagination) "${if(settings.eInkMode) "电子纸" else "分页阅读"} · 点击正文收起工具栏" else "${if(cached) "本地内容 · " else ""}$percent% · 点击正文收起工具栏", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = foreground)
                        }
                    }
                }
                if(settings.staticPagination) Row(Modifier.fillMaxWidth().background(toolbarBackground).height(pageProgressHeight).padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    if(eInk.ready) {
                        val total = eInk.pages.size.coerceAtLeast(1)
                        val current = (eInk.pageIndex + 1).coerceIn(1, total)
                        val chapterPercent = (current.toLong() * 100 / total).toInt()
                        Text("$current / $total", Modifier.testTag("reader-page-counter").semantics { contentDescription = "本章第${current}页，共${total}页" },
                            color = foreground, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                        Text("本章 $chapterPercent%", Modifier.testTag("reader-chapter-progress"), color = foreground, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                    } else Text("正在分页…", color = foreground, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
                }
                if(persistentControls) Spacer(Modifier.fillMaxWidth().background(toolbarBackground).navigationBarsPadding())
            }
            if(chapterLoad.loading || chapterLoad.error != null) Surface(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(readingInsets)
                    .padding(bottom = with(density) { (bottomOverlayHeight - readingInsets.getBottom(density)).coerceAtLeast(0).toDp() })
                    .padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().testTag("reader-chapter-load-status"),
                shape = MaterialTheme.shapes.medium, color = toolbarBackground.copy(alpha = 1f), contentColor = foreground,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(if(chapterLoad.loading) "正在加载章节…" else "章节加载失败，仍在当前章", style = MaterialTheme.typography.titleSmall)
                    chapterLoad.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if(!chapterLoad.loading) TextButton(onClick = chapterLoad::retry) { Text("重试加载章节") }
                        TextButton(onClick = chapterLoad::cancel) { Text(if(chapterLoad.loading) "取消加载" else "继续阅读") }
                    }
                }
            }
        }
        }
        }
        if(nextVolumePrompt && nextVolume != null) AppAlertDialog(onDismissRequest = { nextVolumePrompt = false }, title = { Text("本卷已读完") }, text = { Column { Text("按书架中的分卷顺序接续：${nextVolume.book.title}"); volumeError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { openNextVolume() }, enabled = !openingVolume) { Text(if(openingVolume) "正在打开…" else "阅读下一分卷") } }, dismissButton = { TextButton(onClick = { nextVolumePrompt = false }) { Text("稍后") } })
        if(bookSearch) ReaderSheet(onDismissRequest = { bookSearch = false }) {
            BookSearchPanel(c, ref, chapterId, chapter, settings) { match ->
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
            ReaderTocPane(c, ref, chapterId, tocScroll, tocQuery, { tocQuery = it }, tocReversed, { tocReversed = it }, tocLocateRequest, { tocLocateRequest = 0; tocLocated = true },
                { id -> toc = false; openChapter(id) }, Modifier.fillMaxHeight(.8f))
        }
        if(speechSheet) ReaderSheet(onDismissRequest = { speechSheet = false }) {
            AppScrollColumn(contentModifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("系统朗读", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if(speechStatus == ReadAloudService.SLEEP_TIMER_FINISHED) StickerAccent(MidoriSticker.Sleep, speechStatus, Modifier.size(64.dp))
                    Text(speechStatus.ifBlank { "从当前段落朗读至本章结束。语音由系统提供。" }, style = MaterialTheme.typography.bodyMedium)
                }
                Text("${settings.speechRate}× · ${settings.speechMinutes} 分钟后停止", style = MaterialTheme.typography.labelLarge)
                Button(onClick = {
                    val first = firstParagraph
                    val originalIndex = paragraphs.getOrNull(first)?.index ?: 0
                    val japanese = settings.speechLanguage == "jp" || (settings.speechLanguage == "auto" && settings.mode.startsWith("jp"))
                    scope.launch {
                        try {
                            ReadAloudService.start(context, chapter.title, settings) {
                                if(japanese) chapter.paragraphs.drop(originalIndex)
                                else paragraphs.drop(first).mapNotNull { it.parts.firstOrNull { p -> !p.secondary }?.text }
                            }
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) { c.message(e.friendlyMessage()) }
                    }
                }, Modifier.fillMaxWidth(), enabled = speechStatus != "正在准备朗读…") { Text(if(speechStatus == "正在准备朗读…") "正在准备朗读…" else "从这里开始朗读") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { context.startService(Intent(context, ReadAloudService::class.java).setAction("pause")) }, enabled = speechStatus.startsWith("正在朗读")) { Text("暂停") }
                    TextButton(onClick = { context.startService(Intent(context, ReadAloudService::class.java).setAction("resume")) }, enabled = speechStatus == "朗读已暂停") { Text("继续") }
                    TextButton(onClick = { context.startService(Intent(context, ReadAloudService::class.java).setAction("stop")) }) { Text("停止") }
                    TextButton(onClick = { speechSheet = false; preferences = true }) { Text("设置") }
                }
            }
        }
        selected?.let { paragraph -> ReaderSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(20.dp)) {
                SelectionContainer { Text(paragraph.parts.joinToString("\n\n") { it.text }, Modifier.heightIn(max = 240.dp).appVerticalScroll(rememberScrollState())) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { c.share(paragraph.parts.joinToString("\n\n") { it.text }); selected = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("分享段落") }
                    TextButton(onClick = { saveBookmark(paragraph); selected = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("保存书签") }
                    TextButton(onClick = { saveBookmark(paragraph, edit = true); selected = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑笔记") }
                }
            }
        } }
        note?.let { bookmark -> NoteEditorDialog(bookmark, { note = null }) { text ->
            c.store.update { it.copy(notes = it.notes.map { existing -> if(existing.id == bookmark.id) existing.copy(text = text) else existing }) }
            bookmarkFeedback?.cancel()
            bookmarkFeedback = scope.launch { c.snackbar.showSnackbar("笔记已保存") }
        } }
        }
    }
    if(preferences) ReaderPreferencesSheet(onDismissRequest = { preferences = false }) { expanded, onExpandedChange ->
        ReaderPreferences(settings, local.bookSettings.containsKey(ref.key), { perBook -> c.store.update { it.copy(bookSettings = if(perBook) it.bookSettings + (ref.key to settings) else it.bookSettings - ref.key) } },
            state = preferenceState, modifier = Modifier.fillMaxSize(), livePreview = true, headerActions = {
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(if(expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                        if(expanded) "收起面板" else "展开面板")
                }
                TextButton(onClick = { preferences = false }) { Text("关闭面板") }
            }) { value -> c.store.update { if(it.bookSettings.containsKey(ref.key)) it.copy(bookSettings = it.bookSettings + (ref.key to value)) else it.copy(reader = value) } }
    }
}

// Keep the overlay independent of the outer adaptive Row's size-affecting visibility extension.
@Composable private fun ReaderOverlayVisibility(
    visible: Boolean,
    modifier: Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit
) { AnimatedVisibility(visible, modifier, enter, exit, content = content) }

@Composable internal fun ReaderTextParagraph(paragraph: ReadingParagraph, settings: ReaderSettings, layoutGeneration: Any, foreground: Color, onToggleMenu: () -> Unit, onSelect: () -> Unit, activeMatch: ReadingTextMatch?,
    onLayout: (Int, List<ReadingAnchorLine>) -> Unit) {
    val starts = remember(paragraph, settings.indent, settings.parallel) { paragraphPartStarts(paragraph, settings) }
    val latestLayout by rememberUpdatedState(onLayout)
    Column(Modifier.fillMaxWidth().combinedClickable(
        onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu,
        onLongClickLabel = "选择段落、分享或添加笔记", onLongClick = onSelect
    ), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paragraph.parts.forEachIndexed { partIndex, part ->
            val measurement = remember(layoutGeneration, paragraph) { ScrollPartMeasurement() }
            fun reportLayout() {
                val layout = measurement.layout ?: return
                val top = measurement.top ?: return
                if(measurement.reportedLayout === layout && measurement.reportedTop == top) return
                measurement.reportedLayout = layout
                measurement.reportedTop = top
                latestLayout(partIndex, (0 until layout.lineCount).map { line ->
                    ReadingAnchorLine(starts[partIndex] + layout.getLineStart(line), starts[partIndex] + layout.getLineEnd(line), top + layout.getLineTop(line).roundToInt())
                })
            }
            if(settings.parallel && (part.source == "sakura" || part.source == "gpt" || part.source == "youdao")) Text(part.source.uppercase(), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .65f))
            val highlighted = buildAnnotatedString {
                val indent = if(settings.indent) "　　" else ""
                append(indent); append(part.text)
                activeMatch?.takeIf { it.part == partIndex && it.end > it.start }?.let {
                    val start = (indent.length + it.start).coerceIn(0, length)
                    val end = (indent.length + it.end).coerceIn(start, length)
                    addStyle(SpanStyle(background = foreground.copy(alpha = .2f), textDecoration = TextDecoration.Underline), start, end)
                }
            }
            Text(highlighted,
                modifier = Modifier.onGloballyPositioned { measurement.top = it.positionInParent().y.roundToInt(); reportLayout() },
                onTextLayout = { measurement.layout = it; reportLayout() },
                fontSize = readerPartFontSize(settings, part.secondary).sp,
                lineHeight = readerPartLineHeight(settings, part.secondary).sp,
                fontWeight = if(settings.weight) FontWeight.Bold else FontWeight.Normal,
                color = foreground.copy(alpha = if(part.secondary) settings.secondaryAlpha else 1f),
                textDecoration = if(settings.underline && part.secondary) TextDecoration.Underline else null)
        }
    }
}

private class ScrollPartMeasurement {
    var layout: TextLayoutResult? = null
    var top: Int? = null
    var reportedLayout: TextLayoutResult? = null
    var reportedTop: Int? = null
}

private data class RestoredScrollAnchor(val layoutGeneration: Any, val itemIndex: Int, val pixelOffset: Int, val textOffset: Int)
private data class ReadingRestoreAnchor(val paragraph: Int, val sourceIndex: Int?, val textOffset: Int)
private data class SettledReadingScroll(val index: Int, val offset: Int, val canScrollForward: Boolean, val itemCount: Int, val lastVisibleIndex: Int?)
