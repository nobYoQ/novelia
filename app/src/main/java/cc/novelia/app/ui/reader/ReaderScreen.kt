@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppIconButton
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.lifecycle.LifecycleEventObserver
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
            ReaderContent(c, ref, chapterId, wide = maxWidth >= 840.dp, availableWidth = maxWidth)
        }
    }
    }
}

/**
 * 当前导航项拥有正文与弹层会话，目录数据跨章复用；跨章先准备正文，再保存位置并交接导航状态。
 * 滚动和静态分页共享段落/字符锚点，恢复、拖动预览或重新测量期间不提交中间位置。
 * 已读状态独立于屏顶位置保存，工具栏、搜索框和键盘以浮层叠加，不参与正文分页。
 */
@Composable private fun ReaderContent(c: AppController, ref: BookRef, chapterId: String, wide: Boolean, availableWidth: androidx.compose.ui.unit.Dp) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    val readerFont = rememberReaderFont(settings)
    fun setWideToc(show: Boolean) {
        c.store.update { state ->
            val book = state.bookSettings[ref.key]
            if(book != null) state.copy(bookSettings = state.bookSettings + (ref.key to book.copy(showWideToc = show)))
            else state.copy(reader = state.reader.copy(showWideToc = show))
        }
    }
    // 导航过渡期间，离开的阅读器仍可能处于组合中；此时完成的刷新
    // 不得消费新阅读器的搜索定位或章末跳转标记。
    val readerEntry = remember(ref, chapterId) { c.nav.currentBackStackEntry }
    var menu by rememberSaveable(ref.key) {
        mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerMenuVisible") ?: true)
    }
    var preferences by remember { mutableStateOf(false) }; var toc by remember { mutableStateOf(false) }; var search by remember { mutableStateOf(false) }; var query by rememberSaveable { mutableStateOf("") }; var version by remember { mutableIntStateOf(0) }
    var speechSheet by remember { mutableStateOf(false) }
    var speechRequestPending by remember { mutableStateOf(false) }
    var speechPreferencesRequested by remember { mutableStateOf(false) }
    var autoReadSheet by remember { mutableStateOf(false) }
    var replacementsSheet by rememberSaveable { mutableStateOf(false) }
    var replacementSelection by rememberSaveable { mutableStateOf<String?>(null) }
    var autoReading by remember(ref, chapterId) {
        mutableStateOf(readerEntry?.savedStateHandle?.remove<Boolean>("readerAutoReading") ?: false)
    }
    var editingTapZones by remember { mutableStateOf(false) }
    val preferenceState = rememberReaderPreferencesState(preferences)
    LaunchedEffect(preferences) {
        if(preferences && speechPreferencesRequested) {
            preferenceState.tab.intValue = 3
            preferenceState.expandedGroup.value = "speech"
            speechPreferencesRequested = false
        }
    }
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
    val speechPlayback by ReadAloudService.playback.collectAsStateWithLifecycle()
    val bookSpeech = speechPlayback?.takeIf { it.bookKey == ref.key && it.count > 0 }
    LaunchedEffect(speechStatus, bookSpeech) {
        if(bookSpeech?.playing == true) speechRequestPending = false
        else if(bookSpeech == null && speechRequestPending && speechStatus.isNotBlank() && speechStatus != "正在准备朗读…") {
            speechRequestPending = false; speechSheet = true
        }
    }
    val lifecycleState by readingLifecycle.lifecycle.currentStateFlow.collectAsState()
    fun pauseReadingAutomation() {
        autoReading = false
        if(speechPlayback?.bookKey == ref.key) ReadAloudService.follow(false)
    }
    val context = LocalContext.current; val activity = context.activityOrNull()
    fun speechAction(action: String) {
        if(action == "stop") speechRequestPending = false
        context.startService(Intent(context, ReadAloudService::class.java).setAction(action))
    }
    fun stepSpeech(action: String) {
        autoReading = false; speechSheet = false; menu = false
        ReadAloudService.follow(true)
        speechAction(action)
    }
    fun updateSpeechSettings(value: cc.novelia.app.data.model.ReaderSettings) {
        c.store.update { state ->
            if(state.bookSettings.containsKey(ref.key)) state.copy(bookSettings = state.bookSettings + (ref.key to value))
            else state.copy(reader = value)
        }
    }
    val colors = readerColors(settings, MaterialTheme.colorScheme)
    val reducedMotion = appReducedMotion()
    val focusManager = LocalFocusManager.current
    val background = colors.background
    val foreground = colors.foreground
    val toolbarBackground = colors.toolbar.copy(alpha = 1f - settings.resolvedToolbarTransparency)
    SideEffect { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply { isAppearanceLightStatusBars = background.luminance() > .5f; isAppearanceLightNavigationBars = background.luminance() > .5f } } }
    DisposableEffect(settings.keepScreenOn, settings.brightness, autoReading) {
        val old = activity?.window?.attributes?.screenBrightness
        if(settings.keepScreenOn || autoReading) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        LaunchedEffect(chapter, settings.mode, settings.engines, settings.parallel, settings.traditional, settings.textReplacements, settings.textReplacementsEnabled) {
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
        val chapterNotes = remember(local.notes, ref, chapterId) { local.notes.filter { it.key == ref.key && it.chapterId == chapterId } }
        val noteArrival = remember { readerEntry?.savedStateHandle?.remove<String>("readerNoteId")?.let { id -> chapterNotes.firstOrNull { it.id == id } } }
        val noteArrivalMatch = remember { noteArrival?.let { note -> paragraphs.withIndex().firstNotNullOfOrNull { (index, paragraph) -> noteMatch(note, paragraph, index) } } }
        val annotations = remember(chapterNotes, paragraphs) {
            val bySource = chapterNotes.groupBy { it.paragraph }
            paragraphs.withIndex().flatMap { (index, paragraph) -> bySource[paragraph.index].orEmpty().mapNotNull { noteMatch(it, paragraph, index) } }
        }
        val spokenMatch = remember(speechPlayback, paragraphs, ref, chapterId) {
            speechPlayback?.takeIf { it.bookKey == ref.key && it.chapterId == chapterId }?.segment?.match(paragraphs)
        }
        val searchArrival = remember(ref, chapterId) { readerEntry?.savedStateHandle?.remove<Int>("readerSearchParagraph") }
        val arrivalMatch = remember(ref, chapterId) {
            val state = readerEntry?.savedStateHandle
            val part = state?.remove<Int>("readerSearchPart") ?: 0
            val start = state?.remove<Int>("readerSearchStart") ?: 0
            val end = state?.remove<Int>("readerSearchEnd") ?: start
            searchArrival?.let { ReadingTextMatch(it, part, start, end) }
        }
        var activeMatch by remember { mutableStateOf(arrivalMatch ?: noteArrivalMatch) }
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
            if(noteArrivalMatch != null) Position(chapterId, noteArrivalMatch.paragraph + 1,
                textOffset = noteArrivalMatch.textOffset(paragraphs[noteArrivalMatch.paragraph], settings))
            else if(noteAnchor != null) Position(chapterId,
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
        val replacementsSkipped = remember(paragraphs) { paragraphs.any { it.replacementsSkipped } }
        LaunchedEffect(replacementsSkipped) { if(replacementsSkipped) c.message("部分段落替换后过长，已保留替换前的正文") }
        val replacementSample by produceState("", replacementsSheet, chapter, firstParagraph, settings.mode, settings.traditional) {
            if(replacementsSheet) value = withContext(Dispatchers.Default) {
                val jobContext = currentCoroutineContext()
                prepareReadingParagraphs(chapter, settings.copy(textReplacementsEnabled = false)) { jobContext.ensureActive() }
                    .getOrNull(firstParagraph)?.parts?.firstOrNull()?.text.orEmpty().take(800)
            }
        }
        var selected by remember { mutableStateOf<ReadingParagraph?>(null) }
        var versions by remember { mutableStateOf<ReadingParagraph?>(null) }
        LaunchedEffect(selected, chapter, settings.traditional, settings.textReplacements, settings.textReplacementsEnabled) {
            versions = null
            selected?.let { paragraph ->
                versions = withContext(Dispatchers.Default) {
                    val jobContext = currentCoroutineContext()
                    paragraphVersions(chapter, paragraph.index, settings) { jobContext.ensureActive() } ?: paragraph
                }
            }
        }
        var note by remember { mutableStateOf<Note?>(null) }
        var bookmarkFeedback by remember { mutableStateOf<Job?>(null) }
        fun saveBookmark(paragraph: ReadingParagraph, edit: Boolean = false) {
            val candidate = Note(UUID.randomUUID().toString(), ref.key, chapterId, paragraph.index,
                paragraph.parts.firstOrNull()?.text.orEmpty(), "",
                bookTitle = c.store.state.value.books.firstOrNull { it.book.ref == ref }?.book?.title
                    ?: chapter.novelTitleZh?.takeIf(String::isNotBlank) ?: chapter.novelTitleJp.orEmpty(), chapterTitle = chapter.title)
            c.store.update { state ->
                if(state.notes.any { it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraph.index && it.selection == null })
                    if(edit) state else state.copy(notes = state.notes.map { if(it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraph.index && it.selection == null) it.copy(bookmarked = true) else it })
                else state.copy(notes = state.notes + candidate)
            }
            val saved = c.store.state.value.notes.firstOrNull { it.key == ref.key && it.chapterId == chapterId && it.paragraph == paragraph.index && it.selection == null } ?: return
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
        var speechControlsHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        val statusWidth = availableWidth - (if(wide && settings.showWideToc) 293.dp else 0.dp) -
            with(density) { (safeInsets.getLeft(this, direction) + safeInsets.getRight(this, direction)).toDp() }
        val statusColumns = cc.novelia.app.data.model.ReaderStatusItems.columns(statusWidth.value, density.fontScale)
        val statusRows = cc.novelia.app.data.model.ReaderStatusItems.rows(settings.visibleReadingStatusItems.size, statusColumns)
        // 预留空间只跟随配置、宽度和系统字体变化；时间与进度更新不会重新分页。
        val readingStatusBarHeight = if(statusRows > 0) with(density) { 16.sp.toDp() } * statusRows + 12.dp + 4.dp * (statusRows - 1) else 0.dp
        val persistentBottomHeight = readingStatusBarHeight + with(density) { if(bookSpeech != null) speechControlsHeight.toDp() else 0.dp }
        // 隐藏状态栏时正文延伸到顶部，避免屏幕缺口仍留出整条空白；
        // 保留横屏两侧和底部安全区，目录与引导控件仍避让全部系统区域。
        val readingInsets = if(settings.hideStatusBar) safeInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) else safeInsets
        val safeTop = readingInsets.getTop(density)
        // 恢复定位或重新分页期间的中间画面不能覆盖真实进度。两种模式统一保存正文下标 + 1，
        // 因为滚动列表的第 0 项是章标题；字符偏移支持重排，像素偏移用于恢复原滚动布局。
        fun savePosition() {
            if(leaving || readingLifecycle.lifecycle.currentState == Lifecycle.State.DESTROYED || seekTarget != null || !positionState.initialAnchorRestored || positionState.restoringAnchor || positionState.pendingScrollRestore != null || positionState.previousLayoutGeneration !== layoutGeneration || positionState.previousPagination != settings.staticPagination ||
                (if(settings.staticPagination) !eInk.ready || eInk.pages.isEmpty() else scroll.layoutInfo.totalItemsCount == 0 || scroll.layoutInfo.visibleItemsInfo.isEmpty())) return
            val visible = if(settings.staticPagination) Position(chapterId, eInk.paragraph + 1, 0, chapter.title, textOffset = eInk.textOffset)
                else {
                    val paragraph = paragraphs.getOrNull(scroll.firstVisibleItemIndex - 1)
                    // 页面销毁时文字测量可能已经卸载，不能把缺失几何当作字符 0 写回。
                    if(paragraph != null && paragraph.imageUrl == null && paragraph.localImageId == null &&
                        scrollLayouts[paragraph.index]?.parts?.size != paragraph.parts.size) return
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
            if(!leaving && c.nav.currentBackStackEntry == readerEntry &&
                (!target.automatic || (autoReading && readingLifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))) {
            val continueAutomatic = target.automatic && autoReading
            savePosition()
            c.readPreparedChapter(loaded)
            leaving = true
            c.nav.currentBackStackEntry?.savedStateHandle?.apply {
                // 切换章节会重建阅读器，需一并交接工具栏可见状态。
                set("readerMenuVisible", menu)
                if(continueAutomatic) set("readerAutoReading", true)
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
        DisposableEffect(readingLifecycle, chapterLoad) {
            val observer = LifecycleEventObserver { _, event ->
                if(event == Lifecycle.Event.ON_PAUSE) {
                    autoReading = false
                    if(chapterLoad.target?.automatic == true) chapterLoad.cancel()
                }
            }
            readingLifecycle.lifecycle.addObserver(observer)
            onDispose { readingLifecycle.lifecycle.removeObserver(observer) }
        }
        val contentPanelsClosed = !preferences && !editingTapZones && !search && (!toc || wide) && !autoReadSheet && !replacementsSheet && !bookSearch && !nextVolumePrompt && selected == null && note == null
        val panelsClosed = contentPanelsClosed && !speechSheet
        val showTapTutorial = settings.tapPageTurn && !local.readerTapTutorialSeen && panelsClosed && !leaving &&
            !chapterLoad.loading && chapterLoad.error == null && positionState.initialAnchorRestored && !positionState.restoringAnchor && seekTarget == null &&
            (!settings.staticPagination || eInk.ready)
        cc.novelia.app.ui.toolbar.ReportEdgeToolbarReaderState(readerEntry?.id, menu,
            blocked = !panelsClosed || showTapTutorial || leaving || !positionState.initialAnchorRestored,
            top = if(menu) topOverlayHeight else 0, bottom = bottomOverlayHeight)
        val volumeKeysActive = panelsClosed && !showTapTutorial
        val recordReadingInteraction = rememberReaderReadingTime(c, ref.key,
            local.books.firstOrNull { it.book.ref == ref }?.book?.title ?: chapter.novelTitleZh?.takeIf(String::isNotBlank) ?: chapter.novelTitleJp.orEmpty(),
            local.readingStatistics.revision,
            eligible = local.readingStatistics.enabled && !local.historyPaused && panelsClosed && !showTapTutorial && !leaving &&
                !chapterLoad.loading && chapterLoad.error == null && positionState.initialAnchorRestored && !positionState.restoringAnchor &&
                seekTarget == null && (!settings.staticPagination || eInk.ready) && c.nav.currentBackStackEntry == readerEntry,
            automatic = autoReading || (speechPlayback?.bookKey == ref.key && speechPlayback?.playing == true))
        LaunchedEffect(volumeKeysActive) { if(volumeKeysActive) runCatching { focus.requestFocus() } }
        fun openChapter(id: String, startAtEnd: Boolean = false, match: ReadingTextMatch? = null, inline: Boolean = false,
            restore: ReadingReturnPoint? = null, followingSpeech: Boolean = false, automatic: Boolean = false) {
            if(leaving || id == chapterId) return
            if(!followingSpeech && !automatic) pauseReadingAutomation()
            seekGeneration++; seekJob?.cancel(); seekTarget = null
            inlineChapterLoad = inline
            chapterLoad.request(ReaderChapterTarget(id, startAtEnd, match, restore, automatic))
        }
        fun returnToReadingPlace() {
            pauseReadingAutomation()
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
            pauseReadingAutomation()
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
            recordReadingInteraction()
            pauseReadingAutomation()
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
            recordReadingInteraction()
            pauseReadingAutomation()
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
        suspend fun revealMatch(match: ReadingTextMatch, markSearch: Boolean = true) = withContext(Dispatchers.Main.immediate) {
            val paragraph = paragraphs.getOrNull(match.paragraph) ?: return@withContext
            val textOffset = match.textOffset(paragraph, settings)
            if(markSearch) activeMatch = match
            if(settings.staticPagination) { eInk.find(match.paragraph, textOffset); savePosition() }
            else {
                val overlay = if(menu) (topOverlayHeight - safeTop).coerceAtLeast(0) else 0
                scroll.revealReadingLine(match.paragraph + 1, textOffset, paragraph.parts.size,
                    animate = !markSearch && !reducedMotion, overlay = overlay) { scrollLayouts[paragraph.index] }
                savePosition()
            }
        }
        LaunchedEffect(speechPlayback, lifecycleState, contentPanelsClosed, showTapTutorial, chapterLoad.loading, chapterLoad.error, layoutGeneration, seekTarget, reducedMotion) {
            val current = speechPlayback ?: return@LaunchedEffect
            if(!current.canFollow(ref.key, lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
                    !contentPanelsClosed || showTapTutorial || leaving || chapterLoad.loading || chapterLoad.error != null || seekTarget != null) || c.nav.currentBackStackEntry != readerEntry) return@LaunchedEffect
            snapshotFlow { positionState.initialAnchorRestored && !positionState.restoringAnchor && (!settings.staticPagination || eInk.ready) }.first { it }
            if(current.chapterId != chapterId) openChapter(requireNotNull(current.chapterId), followingSpeech = true)
            else spokenMatch?.let { revealMatch(it, markSearch = false) }
        }
        LaunchedEffect(autoReading, menu, panelsClosed, showTapTutorial, chapterLoad.error) {
            if(menu || !panelsClosed || showTapTutorial || chapterLoad.error != null) autoReading = false
            if(!autoReading && chapterLoad.loading && chapterLoad.target?.automatic == true) chapterLoad.cancel()
        }
        LaunchedEffect(autoReading, lifecycleState, layoutGeneration, chapterLoad.loading, panelsClosed, menu) {
            if(!autoReading || menu || !panelsClosed || chapterLoad.loading || leaving ||
                !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            snapshotFlow { positionState.initialAnchorRestored && !positionState.restoringAnchor &&
                (!settings.staticPagination || eInk.ready) }.first { it }
            val discrete = settings.staticPagination || eInkInteraction
            var previousFrame = android.os.SystemClock.elapsedRealtime()
            var lastAutoSave = previousFrame
            while(autoReading) {
                val atEnd = if(settings.staticPagination) !eInk.canGoForward else !scroll.canScrollForward
                delay(if(discrete || atEnd) settings.resolvedAutoReadIntervalSeconds * 1000L else 32L)
                if(!autoReading || !readingLifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
                if(atEnd) {
                    if(chapter.nextId != null) openChapter(chapter.nextId, automatic = true)
                    else {
                        autoReading = false
                        if(nextVolume != null) nextVolumePrompt = true else c.message("已到末章，自动阅读已暂停")
                    }
                    return@LaunchedEffect
                }
                if(settings.staticPagination) { eInk.move(1); savePosition() }
                else {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val distance = if(discrete) scroll.layoutInfo.viewportSize.height * .85f
                        else autoReadDistance(settings, density.density, now - previousFrame)
                    scroll.scrollBy(distance)
                    previousFrame = now
                    // 持续滚动不会触发原有“停止滚动 500 ms”保存，需要周期性落盘。
                    if(now - lastAutoSave >= 2000) { savePosition(); lastAutoSave = now }
                }
            }
        }
        fun findNext(direction: Int = 1) {
            pauseReadingAutomation()
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
        val highlightLayers = rememberReaderHighlightLayers(if(search) activeMatch else spokenMatch ?: activeMatch,
            animate = !reducedMotion && !search)
        Row(Modifier.fillMaxSize().background(background).testTag(if(wide) "reader-wide-layout" else "reader-compact-layout")) {
        if(wide && settings.showWideToc) {
            Surface(Modifier.width(292.dp).fillMaxHeight().windowInsetsPadding(safeInsets), color = MaterialTheme.colorScheme.surface) {
                Column {
                AppTextButton(onClick = { setWideToc(false) }, modifier = Modifier.align(Alignment.End).testTag("reader-hide-wide-toc")) { Text("收起目录") }
                ReaderTocPane(c, ref, tocState, { scope.launch { tocState.ensureLoaded(chapterId, knownChapterCount, refresh = true) } }, chapterId, tocScroll, tocQuery, { tocQuery = it }, tocReversed, { tocReversed = it }, tocLocateRequest, { tocLocateRequest = 0; tocLocated = true }, { if(it != chapterId) rememberReadingPlace(); openChapter(it) })
                }
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
            val openToc: () -> Unit = { if(wide) { setWideToc(true); tocQuery = ""; tocLocateRequest++ } else toc = true }
            val tapNavigation = rememberReaderTapNavigation(settings.tapPageTurn, bodyInputEnabled, { menu = !menu }, { page(it) }, settings.resolvedTapZoneMap, openToc)
            if(settings.staticPagination) EInkPage(paragraphs, settings, eInk,
                Modifier.testTag("reader-page").then(readerInteractionModifier { recordReadingInteraction(); pauseReadingAutomation() })
                    .align(Alignment.TopCenter).fillMaxHeight().windowInsetsPadding(readingInsets)
                    .padding(bottom = persistentBottomHeight)
                    .widthIn(max = settings.width.dp).fillMaxWidth(),
                imageModel = { it.imageUrl ?: it.localImageId?.takeIf { ref.isLocal }?.let { id -> c.store.documentImage(ref.id, id) } },
                onToggleMenu = { menu = !menu }, onSelect = { selected = it }, onPage = { page(it) },
                background = background, foreground = foreground, activeMatch = if(search) activeMatch else spokenMatch ?: activeMatch, annotations = annotations,
                highlightLayers = highlightLayers,
                interactionEnabled = bodyInputEnabled, contentPadding = PaddingValues(horizontal = settings.resolvedHorizontalMargin.dp, vertical = settings.resolvedVerticalMargin.dp), onNavigate = openToc)
            else LazyColumn(state = scroll, overscrollEffect = null, modifier = Modifier.testTag("reader-scroll")
                .then(readerInteractionModifier { recordReadingInteraction(); pauseReadingAutomation() }).align(Alignment.TopCenter).fillMaxHeight()
                .windowInsetsPadding(readingInsets).padding(bottom = persistentBottomHeight).widthIn(max = settings.width.dp).fillMaxWidth()
                .onSizeChanged { size ->
                    if(positionState.viewportWidth != 0 && positionState.viewportWidth != size.width && scroll.firstVisibleItemIndex > 0)
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
                        if(replacementsSkipped) Text("部分段落替换后过长，已保留替换前的正文。", style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .7f))
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
                    else ReaderTextParagraph(paragraph, settings, layoutGeneration, foreground, tapNavigation::click, { selected = paragraph }, (if(search) activeMatch else spokenMatch ?: activeMatch)?.takeIf { it.paragraph == paragraphIndex }, fontFamily = readerFont.family,
                        annotations = annotations.filter { it.paragraph == paragraphIndex },
                        highlightLayers = highlightLayers.filter { it.match.paragraph == paragraphIndex }) { part, lines ->
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
                            else AppOutlinedButton(onClick = openToc, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回目录") }
                        }
                        // 章末留白让同一提示句位于控件上方，
                        // 不会改变正文视口高度或使章节重新分页。
                        Spacer(Modifier.height(with(density) { (bottomOverlayHeight - readingInsets.getBottom(density)).coerceAtLeast(0).toDp() }
                            .minus(persistentBottomHeight).coerceAtLeast(0.dp)))
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
                onSpeech = { autoReading = false; speechSheet = true },
                onAutoRead = { autoReading = false; autoReadSheet = true }, onReturnToReading = { returnToReadingPlace() },
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
                foreground = foreground, readingStatusBarHeight = readingStatusBarHeight, statusColumns = statusColumns, modifier = Modifier.align(Alignment.BottomCenter),
                onHeightChanged = { bottomOverlayHeight = it }, onPage = { page(it) }, onSeek = { seekChapter(it) },
                onSeekFocusChanged = { seekFocused = it }, onPreviousChapter = { chapter.prevId?.let { openChapter(it) } },
                onNextChapter = { if(chapter.nextId != null) openChapter(chapter.nextId) else nextVolumePrompt = true },
                onToc = { if(wide) { setWideToc(!settings.showWideToc); if(!settings.showWideToc) tocLocateRequest++ } else toc = true },
                onBookmark = { paragraphs.getOrNull(firstParagraph)?.let { saveBookmark(it) } }, onPreferences = { preferences = true },
                onSpeechHeightChanged = { speechControlsHeight = it },
                speechControls = if(bookSpeech == null) null else { {
                    ReaderSpeechDock(bookSpeech, speechStatus,
                        onPrevious = { stepSpeech("previous") }, onNext = { stepSpeech("next") },
                        onPause = { speechAction("pause") }, onResume = { speechAction("resume") },
                        onStop = { speechAction("stop") }, onFollow = ReadAloudService::follow,
                        onExpand = { speechSheet = true }, containerColor = toolbarBackground.copy(alpha = 1f), contentColor = foreground,
                        modifier = Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal)))
                } },
            )
            if(autoReading) Surface(Modifier.align(Alignment.TopEnd).windowInsetsPadding(readingInsets).padding(12.dp)
                .testTag("reader-auto-reading").clickable { autoReading = false },
                color = toolbarBackground.copy(alpha = 1f), contentColor = foreground, shape = MaterialTheme.shapes.small) {
                Text("自动阅读中 · 点按暂停", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
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
                readingInsets, settings.width, persistentBottomHeight, Modifier.matchParentSize(), map = settings.resolvedTapZoneMap) {
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
                    .padding(bottom = persistentBottomHeight).widthIn(max = settings.width.dp).fillMaxWidth(),
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
        if(autoReadSheet) ReaderAutoReadSheet(settings, settings.staticPagination || eInkInteraction,
            onDismiss = { autoReadSheet = false }, onSettings = { value ->
                c.store.update { state ->
                    if(state.bookSettings.containsKey(ref.key)) state.copy(bookSettings = state.bookSettings + (ref.key to value))
                    else state.copy(reader = value)
                }
            }, onStart = {
                pauseReadingAutomation()
                ReadAloudService.pauseForAutomaticReading(context)
                autoReadSheet = false; menu = false; autoReading = true
            })
        if(replacementsSheet) ReaderReplacementsSheet(settings,
            sample = replacementSample,
            initialSource = replacementSelection,
            onDismiss = { replacementsSheet = false; if(replacementSelection == null) preferences = true; replacementSelection = null }, onChange = { value ->
                pauseReadingAutomation()
                ReadAloudService.pauseForAutomaticReading(context)
                c.store.update { it.copy(bookSettings = it.bookSettings + (ref.key to value)) }
            })
        if(speechSheet) ReaderSpeechSheet(settings, speechStatus, onDismiss = { speechSheet = false },
            onStart = {
                menu = false; speechSheet = false; speechRequestPending = true
                val first = firstParagraph
                val originalIndex = paragraphs.getOrNull(first)?.index ?: 0
                scope.launch {
                    try {
                        val jobContext = currentCoroutineContext()
                        ReadAloudService.start(context, chapter.title, settings, ref, chapterId, chapter.nextId) {
                            speechSegments(chapter, settings, originalIndex) { jobContext.ensureActive() }
                        }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { c.message(e.friendlyMessage()) }
                }
            },
            onPause = { speechAction("pause") }, onResume = { speechAction("resume") }, onStop = { speechAction("stop") },
            onSettings = { speechSheet = false; speechPreferencesRequested = true; preferences = true },
            playback = bookSpeech, sample = paragraphs.getOrNull(firstParagraph)?.parts?.firstOrNull()?.text.orEmpty(),
            onPrevious = { stepSpeech("previous") }, onNext = { stepSpeech("next") },
            onFollow = ReadAloudService::follow,
            onRate = { rate ->
                updateSpeechSettings(settings.copy(speechRate = rate))
                if(bookSpeech != null) context.startService(Intent(context, ReadAloudService::class.java).setAction("rate").putExtra("rate", rate))
            },
            onTimer = { minutes ->
                updateSpeechSettings(settings.copy(speechMinutes = minutes))
                if(bookSpeech != null) context.startService(Intent(context, ReadAloudService::class.java).setAction("timer").putExtra("minutes", minutes))
            },
        )
        selected?.let { paragraph -> ReaderParagraphSheet(paragraph, versions, chapterNotes.filter { it.paragraph == paragraph.index }, onDismiss = { selected = null },
            onCopy = { text -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("选文", text)); c.message("选文已复制") },
            onShare = c::share, onLookup = { lookupReaderText(context, it, c::message) },
            onSpeak = { quote, selection ->
                selected = null; menu = false; speechSheet = false; speechRequestPending = true
                val japanese = selection.source == "original" && (!ref.isLocal || quote.any { it in '\u3040'..'\u30ff' })
                scope.launch {
                    try {
                        val job = currentCoroutineContext()
                        ReadAloudService.start(context, "选文朗读 · ${chapter.title}", settings.copy(speechContinueChapters = false, speechLanguage = if(japanese) "jp" else "zh"), ref, chapterId) {
                            speechSelection(quote, paragraph.index, selection.source, selection.occurrence, selection.start) { job.ensureActive() }
                        }
                    } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { c.message(e.friendlyMessage()) }
                }
            },
            onReplace = { quote ->
                replacementSelection = quote; selected = null; replacementsSheet = true; pauseReadingAutomation()
            },
            onBookmark = { saveBookmark(paragraph); selected = null },
            onAnnotate = { quote, selection, edit ->
                val existing = chapterNotes.firstOrNull { it.paragraph == paragraph.index && it.selection == selection && it.quote == quote }
                val candidate = existing ?: Note(UUID.randomUUID().toString(), ref.key, chapterId, paragraph.index, quote, "",
                    bookTitle = local.books.firstOrNull { it.book.ref == ref }?.book?.title
                        ?: chapter.novelTitleZh?.takeIf(String::isNotBlank) ?: chapter.novelTitleJp.orEmpty(),
                    chapterTitle = chapter.title, bookmarked = false, selection = selection)
                if(edit) { note = candidate; selected = null }
                else { if(existing == null) c.store.update { it.copy(notes = it.notes + candidate) }; c.message(if(existing == null) "划线已保存" else "此选文已保存") }
            },
            onEditNote = { note = it; selected = null },
            onNotes = { selected = null; c.go("notes"); c.nav.currentBackStackEntry?.savedStateHandle?.set("notesBookKey", ref.key) },
            onDeleteNote = { removed ->
                c.store.update { it.copy(notes = it.notes.filterNot { n -> n.id == removed.id }) }
                scope.launch {
                    if(c.snackbar.showSnackbar("记录已删除", actionLabel = "撤销", withDismissAction = true) == SnackbarResult.ActionPerformed)
                        c.store.update { if(it.notes.any { n -> n.id == removed.id }) it else it.copy(notes = it.notes + removed) }
                }
            },
        ) }
        note?.let { bookmark -> NoteEditorDialog(bookmark, { note = null }) { text ->
            c.store.update { state ->
                val updated = if(state.notes.any { it.id == bookmark.id }) state.notes.map { if(it.id == bookmark.id) it.copy(text = text) else it }
                    else state.notes + bookmark.copy(text = text)
                state.copy(notes = updated.filter { it.bookmarked || it.text.isNotBlank() || it.selection != null })
            }
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
                onTextReplacements = { preferences = false; replacementsSheet = true; pauseReadingAutomation() },
                onShowTapTutorial = { preferences = false; c.store.update { it.copy(readerTapTutorialSeen = false) } }, headerActions = {
                    AppIconButton(onClick = { onExpandedChange(!expanded) }) {
                        ReaderSheetExpandIcon(expanded)
                    }
                    AppTextButton(onClick = { preferences = false }) { Text("关闭面板") }
                }) { value -> c.store.update { if(it.bookSettings.containsKey(ref.key)) it.copy(bookSettings = it.bookSettings + (ref.key to value)) else it.copy(reader = value) } }
        }
    }
}
