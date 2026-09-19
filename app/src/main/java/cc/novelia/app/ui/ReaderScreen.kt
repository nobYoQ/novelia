@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import cc.novelia.app.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

@Composable fun ReaderScreen(c: AppController, ref: BookRef, chapterId: String) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    val eInk = settings.eInkMode
    val appEInk = LocalEInkMode.current || eInk
    AppInteractionMode(appEInk, LocalReducedMotion.current || eInk) {
        ReaderContent(c, ref, chapterId)
    }
}

@Composable private fun ReaderContent(c: AppController, ref: BookRef, chapterId: String) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    var menu by rememberSaveable(ref.key) {
        mutableStateOf(c.nav.currentBackStackEntry?.savedStateHandle?.remove<Boolean>("readerMenuVisible") ?: true)
    }
    var preferences by remember { mutableStateOf(false) }; var toc by remember { mutableStateOf(false) }; var search by remember { mutableStateOf(false) }; var query by rememberSaveable { mutableStateOf("") }; var version by remember { mutableIntStateOf(0) }
    var speechSheet by remember { mutableStateOf(false) }
    var bookSearch by remember { mutableStateOf(false) }
    var refreshAnchor by remember(ref, chapterId) { mutableStateOf<ReadingRestoreAnchor?>(null) }
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val enteredCacheGeneration = remember(ref, chapterId) { c.store.cacheGeneration.value }
    val readingLifecycle = LocalLifecycleOwner.current
    val speechStatus by ReadAloudService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current; val activity = context.activityOrNull()
    val colors = readerColors(settings.resolvedTheme, MaterialTheme.colorScheme)
    val reducedMotion = LocalReducedMotion.current
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
    BackHandler(preferences || toc || search || bookSearch) { preferences = false; toc = false; search = false; bookSearch = false }
    AsyncContent(listOf(ref, chapterId), load = { c.chapter(ref, chapterId, version > 0) }, refreshKey = version) { (chapter, cached), _ ->
        var prepared by remember(ref, chapterId, chapter) { mutableStateOf<List<ReadingParagraph>?>(null) }
        LaunchedEffect(chapter, settings.mode, settings.engines, settings.parallel, settings.traditional) {
            prepared = withContext(Dispatchers.Default) { prepareReadingParagraphs(chapter, settings) }
        }
        val paragraphs = prepared
        if(paragraphs == null) {
            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if(!LocalEInkMode.current) CircularProgressIndicator(color = foreground)
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
        val searchArrival = remember(ref, chapterId) { c.nav.currentBackStackEntry?.savedStateHandle?.remove<Int>("readerSearchParagraph") }
        val position = remember(ref, chapterId) {
            val anchor = refreshAnchor
            if(searchArrival != null) Position(chapterId, searchArrival.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) + 1)
            else if(anchor != null) Position(chapterId, (anchor.sourceIndex?.let { source -> paragraphs.indexOfFirst { it.index >= source }.takeIf { it >= 0 } } ?: anchor.paragraph).coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) + 1, textOffset = anchor.textOffset)
            else if(c.nav.currentBackStackEntry?.savedStateHandle?.remove<Boolean>("readerStartAtEnd") == true) Position(chapterId, Int.MAX_VALUE)
            else local.positions[ref.key]?.takeIf { it.chapterId == chapterId }
        }
        val scroll = rememberLazyListState(position?.index ?: 0, position?.offset ?: 0); val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
        val eInk = remember { EInkPageState(position) }
        val layoutGeneration = remember(paragraphs, settings.fontSize, settings.lineHeight, settings.width, settings.indent, settings.parallel, settings.weight, settings.staticPagination) { Any() }
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
        var selected by remember { mutableStateOf<ReadingParagraph?>(null) }; var note by remember { mutableStateOf<ReadingParagraph?>(null) }
        var leaving by remember { mutableStateOf(false) }
        var nextVolumePrompt by remember { mutableStateOf(false) }
        var openingVolume by remember { mutableStateOf(false) }
        var volumeError by remember { mutableStateOf<String?>(null) }
        val nextVolume = remember(local.books, ref) { local.nextMountedVolume(ref) }
        var lastSavedPosition by remember { mutableStateOf<Position?>(null) }
        var finding by remember { mutableStateOf(false) }
        var topOverlayHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        // Reserve a stable footer in paged mode, independent of toolbar/button visibility.
        val pageProgressHeight = with(density) { 16.sp.toDp() } + 12.dp
        // Search and its keyboard are overlays too; only system bars/cutouts bound the reading viewport.
        val readingInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val safeTop = readingInsets.getTop(density)
        val volumeKeysActive = !preferences && !search && !toc && !speechSheet && !bookSearch && !nextVolumePrompt && selected == null && note == null
        LaunchedEffect(volumeKeysActive) { if(volumeKeysActive) runCatching { focus.requestFocus() } }
        fun savePosition() {
            if(leaving || restoringAnchor || previousPagination != settings.staticPagination || (if(settings.staticPagination) !eInk.ready else scroll.layoutInfo.totalItemsCount == 0) || c.store.state.value.historyPaused) return
            val next = if(settings.staticPagination) Position(chapterId, eInk.paragraph + 1, 0, chapter.title, textOffset = eInk.textOffset)
                else {
                    val paragraph = paragraphs.getOrNull(scroll.firstVisibleItemIndex - 1)
                    val textOffset = scrollTextOffset(paragraph?.index)
                    Position(chapterId, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, chapter.title, textOffset = textOffset)
                }
            val previous = lastSavedPosition
            if(previous == null || previous.chapterId != next.chapterId || previous.index != next.index || previous.offset != next.offset || previous.textOffset != next.textOffset || previous.title != next.title) {
                c.store.savePosition(ref, next)
                lastSavedPosition = next
            }
        }
        fun openChapter(id: String, startAtEnd: Boolean = false) {
            if(leaving || id == chapterId) return
            savePosition()
            leaving = true
            c.nav.popBackStack()
            c.read(ref, id)
            c.nav.currentBackStackEntry?.savedStateHandle?.apply {
                // Chapter navigation recreates the reader; carry its toolbar state forward.
                set("readerMenuVisible", menu)
                if(startAtEnd) set("readerStartAtEnd", true)
            }
        }
        fun refreshChapter() {
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
                        val chapters = c.store.document(target.book.ref.id).chapters
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
            scope.launch { if(reducedMotion) scroll.scrollBy(distance * direction) else scroll.animateScrollBy(distance * direction) }
        }
        fun findNext() {
            if(query.isBlank() || finding) return
            val term = query
            val after = firstParagraph
            finding = true
            scope.launch {
                try {
                    val match = withContext(Dispatchers.Default) { findNextReadingParagraph(paragraphs, term, after) }
                    if(match >= 0) {
                        val offset = -(topOverlayHeight - safeTop).coerceAtLeast(0)
                        if(settings.staticPagination) { eInk.find(match); savePosition() }
                        else if(reducedMotion) scroll.scrollToItem(match + 1, offset) else scroll.animateScrollToItem(match + 1, offset)
                    } else c.message("没有找到匹配文字")
                } finally { finding = false }
            }
        }
        BackHandler(!preferences && !toc && !search && !speechSheet && !bookSearch && !nextVolumePrompt && selected == null && note == null) {
            if(!leaving) { savePosition(); leaving = true; c.back() }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        // Local callable references compare by declaration, so rememberUpdatedState can
        // retain a reference whose captured settings belong to the previous reading mode.
        val latestSavePosition by rememberUpdatedState<() -> Unit>({ savePosition() })
        DisposableEffect(lifecycleOwner, ref, chapterId) {
            val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) latestSavePosition() }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer); latestSavePosition() }
        }
        LaunchedEffect(scroll, chapterId, local.historyPaused) {
            if(!local.historyPaused) snapshotFlow {
                if(scroll.isScrollInProgress) null else scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset
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
        LaunchedEffect(searchArrival) {
            if(searchArrival != null && !settings.staticPagination) {
                if(menu) snapshotFlow { topOverlayHeight }.first { it > 0 }
                scroll.scrollToItem(searchArrival.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)) + 1, if(menu) -(topOverlayHeight - safeTop).coerceAtLeast(0) else 0)
            }
        }
        LaunchedEffect(chapterId) {
            if(c.session.profile.value != null && !ref.isLocal && !local.historyPaused) {
                try { c.cloudMutation("PUT", "user/read-history/${ref.key}", chapterId, "text/plain") }
                catch(e: CancellationException) { throw e }
                catch(_: Exception) { /* Local progress remains available when history sync fails. */ }
            }
        }
        // Keep reader-specific colors inside the page; all settings sheets inherit the app theme.
        ReaderPageTheme(settings.resolvedTheme == "monochrome") {
        Box(Modifier.fillMaxSize().background(background).focusRequester(focus).onPreviewKeyEvent { event ->
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
                background = background, foreground = foreground)
            else LazyColumn(state = scroll, modifier = Modifier.testTag("reader-scroll").align(Alignment.TopCenter).fillMaxHeight()
                .windowInsetsPadding(readingInsets).widthIn(max = settings.width.dp).fillMaxWidth()
                .readerChapterOverscroll(scroll, chapter.nextId != null && !leaving && !restoringAnchor && volumeKeysActive) {
                    chapter.nextId?.let { openChapter(it) }
                }
                , contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item("title", contentType = "title") {
                    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "显示或收起阅读工具栏") { menu = !menu }) {
                        Text(chapter.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = foreground)
                        Spacer(Modifier.height(12.dp))
                        Text(if(ref.isLocal) "本地小说" else (providers[ref.provider].orEmpty() + " · " + if(settings.mode == "jp") "日文原文" else "机翻阅读"), style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .65f))
                        if(hasFallback) Text("部分段落暂无所选译文，显示原文。", style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .7f))
                    }
                }
                itemsIndexed(paragraphs, key = { _, p -> "paragraph-${p.index}" }, contentType = { _, p -> if(p.imageUrl != null || p.localImageId != null) "image" else "paragraph" }) { _, paragraph ->
                    DisposableEffect(paragraph.index, scrollLayouts) {
                        onDispose { scrollLayouts.remove(paragraph.index) }
                    }
                    val image = remember(ref, paragraph.imageUrl, paragraph.localImageId) {
                        paragraph.imageUrl ?: paragraph.localImageId?.takeIf { ref.isLocal }?.let { c.store.documentImage(ref.id, it) }
                    }
                    if(image != null) ReaderIllustration(image, foreground) { menu = !menu }
                    else ReaderTextParagraph(paragraph, settings, layoutGeneration, foreground, { menu = !menu }, { selected = paragraph }) { part, lines ->
                        val existing = scrollLayouts[paragraph.index] ?: ParagraphScrollLayout()
                        if(existing.parts[part] != lines) scrollLayouts[paragraph.index] = existing.copy(parts = existing.parts + (part to lines))
                    }
                }
                item("end", contentType = "footer") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(Modifier.padding(vertical = 24.dp)); Text("本章完", color = foreground.copy(alpha = .65f)); Spacer(Modifier.height(20.dp))
                        if(chapter.nextId != null) Text("继续上滑，松手阅读下一章", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .65f))
                        if(chapter.nextId != null) Button(onClick = { openChapter(chapter.nextId) }, enabled = !leaving) { Text("阅读下一章") }
                        else if(nextVolume != null) { Text("下一分卷：${nextVolume.book.title}", color = foreground, modifier = Modifier.padding(bottom = 12.dp)); Button(onClick = { nextVolumePrompt = true }, enabled = !leaving) { Text("阅读下一分卷") } }
                        else OutlinedButton(onClick = { toc = true }) { Text("返回目录") }
                    }
                }
            }
            AnimatedVisibility(menu, Modifier.align(Alignment.TopCenter), enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(180)) + slideInVertically(tween(220)) { -it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it }) {
                Surface(Modifier.testTag("reader-top-toolbar"), color = toolbarBackground, contentColor = foreground) {
                // This height is used only to place search results below the overlay.
                Column(Modifier.onSizeChanged { topOverlayHeight = it.height }) {
                    TopAppBar(title = { Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
                        IconButton(onClick = { if(!leaving) { savePosition(); leaving = true; c.back() } }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    }, actions = {
                        if(!ref.isLocal) IconButton(onClick = { refreshChapter() }, enabled = !leaving) { Icon(Icons.Outlined.Refresh, "刷新本章译文") }
                        IconButton(onClick = { if(search) focusManager.clearFocus(); search = !search }) { Icon(Icons.Outlined.Search, "搜索本章") }
                        IconButton(onClick = { preferences = true }) { Icon(Icons.Outlined.TextFields, "阅读设置") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent, titleContentColor = foreground, actionIconContentColor = foreground, navigationIconContentColor = foreground))
                    if(translationUpdate != null) TextButton(onClick = { refreshChapter() }, enabled = !leaving, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("本书有新的译文，可刷新本章") }
                    AnimatedVisibility(search,
                        enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(160)) + expandVertically(tween(220), expandFrom = Alignment.Top),
                        exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(100)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top)
                    ) {
                        Column {
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(query, { query = it }, label = { Text("搜索本章段落") }, singleLine = true, modifier = Modifier.weight(1f), enabled = search,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = foreground, unfocusedTextColor = foreground,
                                    focusedBorderColor = foreground, unfocusedBorderColor = foreground.copy(alpha = .5f),
                                    focusedLabelColor = foreground, unfocusedLabelColor = foreground.copy(alpha = .7f), cursorColor = foreground))
                            TextButton(onClick = { findNext() }, enabled = search && query.isNotBlank() && !finding,
                                colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f))) { Text(if(finding) "查找中" else "查找") }
                        }
                        TextButton(onClick = { focusManager.clearFocus(); bookSearch = true }, Modifier.padding(horizontal = 12.dp), colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("整本搜索（本地 / 已缓存章节）") }
                        }
                    }
                }
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).testTag("reader-bottom-toolbar"), color = toolbarBackground, contentColor = foreground) {
            Column(Modifier.navigationBarsPadding()) {
                if(settings.showPageButtons) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { page(-1) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f)), enabled = if(settings.staticPagination) eInk.ready && (eInk.canGoBack || (eInk.pages.isNotEmpty() && chapter.prevId != null)) else scroll.canScrollBackward) { Text(if(settings.staticPagination) "上一页" else "上一屏") }
                    OutlinedButton(onClick = { page(1) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f)), enabled = if(settings.staticPagination) eInk.ready && (eInk.canGoForward || (eInk.pages.isNotEmpty() && (chapter.nextId != null || nextVolume != null))) else scroll.canScrollForward) { Text(if(settings.staticPagination) "下一页" else "下一屏") }
                }
                AnimatedVisibility(menu, enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(180)) + slideInVertically(tween(220)) { it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(140)) + slideOutVertically(tween(180)) { it }) {
                    Surface(color = Color.Transparent, contentColor = foreground) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { chapter.prevId?.let { openChapter(it) } }, enabled = chapter.prevId != null && !leaving) { Icon(Icons.Outlined.SkipPrevious, "上一章") }
                                TextButton(onClick = { toc = true }, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(18.dp)); Text(" 目录") }
                                IconButton(onClick = { note = paragraphs.getOrNull(firstParagraph) }, enabled = paragraphs.isNotEmpty()) { Icon(Icons.Outlined.BookmarkAdd, "添加书签或笔记") }
                                IconButton(onClick = { speechSheet = true }) {
                                    if(speechStatus == ReadAloudService.SLEEP_TIMER_FINISHED) StickerAccent(MidoriSticker.Sleep, speechStatus, Modifier.size(40.dp).semantics { contentDescription = "朗读定时已结束，打开朗读设置" })
                                    else Icon(Icons.Outlined.VolumeUp, "朗读本章")
                                }
                                IconButton(onClick = { if(chapter.nextId != null) openChapter(chapter.nextId) else nextVolumePrompt = true }, enabled = (chapter.nextId != null || nextVolume != null) && !leaving) { Icon(Icons.Outlined.SkipNext, if(chapter.nextId == null && nextVolume != null) "下一分卷" else "下一章") }
                            }
                            Text(if(settings.staticPagination) "${if(settings.eInkMode) "电子纸" else "分页阅读"} · 点击正文收起工具栏" else "${if(cached) "本地内容 · " else ""}$percent% · 点击正文收起工具栏", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = foreground)
                        }
                    }
                }
                if(settings.staticPagination) Row(Modifier.fillMaxWidth().height(pageProgressHeight).padding(horizontal = 24.dp),
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
            }
            }
        }
        }
        if(nextVolumePrompt && nextVolume != null) AlertDialog(onDismissRequest = { nextVolumePrompt = false }, title = { Text("本卷已读完") }, text = { Column { Text("按书架中的分卷顺序接续：${nextVolume.book.title}"); volumeError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(onClick = { openNextVolume() }, enabled = !openingVolume) { Text(if(openingVolume) "正在打开…" else "阅读下一分卷") } }, dismissButton = { TextButton(onClick = { nextVolumePrompt = false }) { Text("稍后") } })
        if(bookSearch) ReaderSheet(onDismissRequest = { bookSearch = false }) {
            BookSearchPanel(c, ref, chapterId, chapter, settings) { match ->
                bookSearch = false; search = false; focusManager.clearFocus()
                if(match.chapterId == chapterId) scope.launch {
                    if(settings.staticPagination) { eInk.find(match.paragraph); savePosition() }
                    else scroll.scrollToItem(match.paragraph + 1, -(topOverlayHeight - safeTop).coerceAtLeast(0))
                } else {
                    openChapter(match.chapterId)
                    c.nav.currentBackStackEntry?.savedStateHandle?.set("readerSearchParagraph", match.paragraph)
                }
            }
        }
        if(toc) ReaderSheet(onDismissRequest = { toc = false }) {
            AsyncContent(ref.key, load = { withContext(Dispatchers.IO) { if(ref.isLocal) c.store.document(ref.id).chapters.map { TocItem(it.title, it.title, it.id) } else c.detail<WebDetail>("novel/${ref.key}").toc } }, modifier = Modifier.fillMaxHeight(.8f)) { list, _ -> TocPanel(c, ref, list, chapterId) { id -> toc = false; openChapter(id) } }
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
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { TextButton(onClick = { c.share(paragraph.parts.joinToString("\n\n") { it.text }); selected = null }) { Text("分享段落") }; TextButton(onClick = { note = paragraph; selected = null }) { Text("书签 / 笔记") } }
            }
        } }
        note?.let { paragraph -> var text by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { note = null }, title = { Text("保存书签或笔记") }, text = { OutlinedTextField(text, { text = it }, label = { Text("笔记（可留空）") }, minLines = 3) }, confirmButton = { TextButton(onClick = { c.store.update { it.copy(notes = it.notes + Note(UUID.randomUUID().toString(), ref.key, chapterId, paragraph.index, paragraph.parts.firstOrNull()?.text.orEmpty(), text)) }; note = null; c.message("已保存到我的笔记") }) { Text("保存") } }, dismissButton = { TextButton(onClick = { note = null }) { Text("取消") } }) }
        }
    }
    if(preferences) ReaderSheet(onDismissRequest = { preferences = false }) { ReaderPreferences(settings, local.bookSettings.containsKey(ref.key), { perBook -> c.store.update { it.copy(bookSettings = if(perBook) it.bookSettings + (ref.key to settings) else it.bookSettings - ref.key) } }) { value -> c.store.update { if(it.bookSettings.containsKey(ref.key)) it.copy(bookSettings = it.bookSettings + (ref.key to value)) else it.copy(reader = value) } } }
}

@Composable private fun ReaderTextParagraph(paragraph: ReadingParagraph, settings: ReaderSettings, layoutGeneration: Any, foreground: Color, onToggleMenu: () -> Unit, onSelect: () -> Unit,
    onLayout: (Int, List<ReadingAnchorLine>) -> Unit) {
    val starts = remember(paragraph, settings.indent, settings.parallel) { paragraphPartStarts(paragraph, settings) }
    val latestLayout by rememberUpdatedState(onLayout)
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
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
            Text((if(settings.indent) "　　" else "") + part.text,
                modifier = Modifier.onGloballyPositioned { measurement.top = it.positionInParent().y.roundToInt(); reportLayout() },
                onTextLayout = { measurement.layout = it; reportLayout() },
                fontSize = (if(part.secondary) settings.fontSize - 1 else settings.fontSize).sp,
                lineHeight = (settings.fontSize * settings.lineHeight).sp,
                fontWeight = if(settings.weight) FontWeight.Medium else FontWeight.Normal,
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

@Composable internal fun ReaderIllustration(model: Any, foreground: Color, onToggleMenu: () -> Unit) {
    val context = LocalContext.current
    val animate = !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()
    val request = remember(context, model, animate) { ImageRequest.Builder(context).data(model).crossfade(if(animate) 180 else 0).build() }
    var loading by remember(model) { mutableStateOf(true) }
    var failed by remember(model) { mutableStateOf(false) }
    var expanded by remember(model) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Reserve a portrait illustration frame before decoding so incoming images cannot shift later paragraphs.
        Box(Modifier.fillMaxWidth().height((maxWidth * 1.35f).coerceIn(180.dp, 900.dp))
            .background(foreground.copy(alpha = .035f))
            .combinedClickable(onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu,
                onLongClickLabel = "放大查看插图", onLongClick = { expanded = true }), contentAlignment = Alignment.Center) {
            AsyncImage(request, "小说插图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onLoading = { loading = true; failed = false }, onSuccess = { loading = false; failed = false }, onError = { loading = false; failed = true })
            if(loading) { if(LocalEInkMode.current) Text("正在加载插图…", color = foreground) else CircularProgressIndicator(Modifier.size(28.dp), color = foreground.copy(alpha = .65f), strokeWidth = 2.dp) }
            if(failed) Text("插图暂时无法加载", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = foreground.copy(alpha = .7f))
        }
    }
    if(expanded) IllustrationViewer(model) { expanded = false }
}

@Composable fun ReaderPreferences(value: ReaderSettings, perBook: Boolean? = null, onPerBook: (Boolean) -> Unit = {}, onChange: (ReaderSettings) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    AppScrollColumn(contentModifier = Modifier.padding(bottom = 28.dp)) {
        Text("阅读偏好", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        if(perBook != null) TogglePreference("仅应用于这本书", "为当前小说保存独立设置", perBook, onPerBook)
        TogglePreference("电子纸阅读模式", "首次开启使用自动分页。作为默认偏好时，全应用改为按屏翻动和按钮排序；仅应用于这本书时只影响当前阅读器。保留主题，关闭后恢复普通交互。", value.eInkMode) { onChange(value.withEInkMode(it)) }
        ChoiceRow("分页模式", listOf("连续滚动", "自动分页"), if(value.staticPagination) 1 else 0) { onChange(value.withPaginationMode(if(it == 1) "auto" else "scroll")) }
        Text(if(value.staticPagination) "按屏幕大小提前排成独立页面，每次翻动一页。" else "整章连续排列，上下滑动浏览，不提前拆成独立页面。",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(value.staticPagination) Column(Modifier.padding(start = 20.dp, end = 12.dp)) {
            Text("自动分页手势", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            TogglePreference("滚动翻页", "向上滑动下一页，向下滑动上一页", value.scrollPageTurn) { onChange(value.copy(scrollPageTurn = it)) }
            TogglePreference("左右翻页", "向左滑动下一页，向右滑动上一页", value.horizontalPageTurn) { onChange(value.copy(horizontalPageTurn = it)) }
        }
        TogglePreference("显示翻页按钮", if(value.staticPagination) "显示上一页、下一页按钮" else "显示上一屏、下一屏，每次移动约一屏正文", value.showPageButtons) { onChange(value.copy(showPageButtons = it)) }
        ReaderSlider("工具栏透明度 ${(value.resolvedToolbarTransparency * 100).roundToInt()}%", value.resolvedToolbarTransparency, 0f..1f,
            modifier = Modifier.testTag("reader-toolbar-transparency")) { onChange(value.copy(toolbarTransparency = it)) }
        Text("0% 为不透明，100% 为背景完全透明；文字和图标保持清晰。工具栏覆盖正文，显示或收起不会改变排版。",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceRow("显示语言", listOf("中文", "日文", "中日", "日中"), listOf("zh", "jp", "zh-jp", "jp-zh").indexOf(value.mode)) { onChange(value.copy(mode = listOf("zh", "jp", "zh-jp", "jp-zh")[it])) }
        ChoiceRow("优先译文", listOf("Sakura", "GPT", "有道"), listOf("sakura", "gpt", "youdao").indexOf(value.engines.firstOrNull())) { val engine = listOf("sakura", "gpt", "youdao")[it]; onChange(value.copy(engines = listOf(engine) + value.engines.filterNot { e -> e == engine })) }
        TogglePreference("并列展示译文", "关闭时按优先顺序回退", value.parallel) { onChange(value.copy(parallel = it)) }
        ChoiceRow("自动预读后续章节", listOf("关闭", "1 章", "3 章", "5 章"), listOf(0, 1, 3, 5).indexOf(value.prefetchChapters).coerceAtLeast(0)) { onChange(value.copy(prefetchChapters = listOf(0, 1, 3, 5)[it])) }
        TogglePreference("仅 Wi-Fi 自动预读", "每次进入章节后依次预读，退出阅读或清理缓存即停止", value.prefetchWifiOnly) { onChange(value.copy(prefetchWifiOnly = it)) }
        ReaderSlider("字号 ${value.fontSize.toInt()}", value.fontSize, 14f..32f) { onChange(value.copy(fontSize = it)) }
        ReaderSlider("行距 ${"%.1f".format(value.lineHeight)}", value.lineHeight, 1.3f..2.6f) { onChange(value.copy(lineHeight = it)) }
        ReaderSlider("内容宽度 ${value.width.toInt()} dp", value.width, 300f..900f) { onChange(value.copy(width = it)) }
        ReaderSlider("辅文本不透明度 ${(value.secondaryAlpha * 100).toInt()}%", value.secondaryAlpha, .45f..1f) { onChange(value.copy(secondaryAlpha = it)) }
        ChoiceRow("阅读主题", listOf("跟随应用", "纸张", "浅色", "深色", "黑白"), listOf("system", "paper", "light", "dark", "monochrome").indexOf(value.resolvedTheme)) { onChange(value.withTheme(listOf("system", "paper", "light", "dark", "monochrome")[it])) }
        TogglePreference("加粗文字", "中等字重", value.weight) { onChange(value.copy(weight = it)) }
        TogglePreference("首行缩进", "统一为两个全角空格", value.indent) { onChange(value.copy(indent = it)) }
        TogglePreference("繁体显示", "将简体译文转换为繁体", value.traditional) { onChange(value.copy(traditional = it)) }
        TogglePreference("辅文本下划线", "用于双语对照", value.underline) { onChange(value.copy(underline = it)) }
        TogglePreference("屏幕常亮", "仅在阅读器中生效", value.keepScreenOn) { onChange(value.copy(keepScreenOn = it)) }
        TogglePreference("音量键翻页", "音量键控制阅读位置", value.volumeKeys) { onChange(value.copy(volumeKeys = it)) }
        TogglePreference("跟随系统亮度", "关闭后可单独调整", value.brightness < 0) { onChange(value.copy(brightness = if(it) -1f else .5f)) }
        AnimatedVisibility(value.brightness >= 0,
            enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(160)) + expandVertically(tween(220), expandFrom = Alignment.Top),
            exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(100)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top)
        ) {
            ReaderSlider("屏幕亮度", value.brightness.coerceIn(.05f, 1f), .05f..1f, enabled = value.brightness >= 0) { onChange(value.copy(brightness = it)) }
        }
        ReaderSlider("朗读速度 ${"%.1f".format(value.speechRate)}×", value.speechRate, .5f..2f) { onChange(value.copy(speechRate = it)) }
        ChoiceRow("朗读语言", listOf("随显示模式", "中文", "日文"), listOf("auto", "zh", "jp").indexOf(value.speechLanguage)) { onChange(value.copy(speechLanguage = listOf("auto", "zh", "jp")[it])) }
        ChoiceRow("朗读定时停止", listOf("15 分钟", "30 分钟", "60 分钟"), listOf(15, 30, 60).indexOf(value.speechMinutes)) { onChange(value.copy(speechMinutes = listOf(15, 30, 60)[it])) }
    }
}
@Composable fun TogglePreference(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = if(subtitle.isNotBlank()) ({ Text(subtitle) }) else null, trailingContent = {
    if(LocalEInkMode.current) Icon(if(value) Icons.Outlined.ToggleOn else Icons.Outlined.ToggleOff, null, Modifier.size(48.dp), tint = if(value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    else Switch(value, onCheckedChange = null)
}, modifier = Modifier.toggleable(value = value, role = Role.Switch, onValueChange = onChange)) }
@Composable private fun ReaderSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if(LocalEInkMode.current) {
            val step = when { range.endInclusive - range.start > 100f -> 20f; range.endInclusive - range.start > 10f -> 1f; else -> .05f }
            Row(modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
                if(!enabled) disabled()
                setProgress { next -> if(enabled && next.isFinite()) { onChange(next.coerceIn(range)); true } else false }
            }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = enabled && value > range.start) { Icon(Icons.Outlined.Remove, "减小 $label") }
                Text("${(value * 100).roundToInt() / 100f}", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = enabled && value < range.endInclusive) { Icon(Icons.Outlined.Add, "增大 $label") }
            }
        } else {
            // Keep thumb feedback immediate while committing expensive typography changes once.
            var draft by remember(value) { mutableFloatStateOf(value) }
            Slider(draft, { draft = it }, modifier = modifier, valueRange = range, enabled = enabled,
                onValueChangeFinished = { onChange(draft) })
        }
    }
}
