@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
import androidx.compose.foundation.verticalScroll
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import cc.novelia.app.reader.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable fun ReaderScreen(c: AppController, ref: BookRef, chapterId: String) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    var menu by remember { mutableStateOf(true) }; var preferences by remember { mutableStateOf(false) }; var toc by remember { mutableStateOf(false) }; var search by remember { mutableStateOf(false) }; var query by rememberSaveable { mutableStateOf("") }; var version by remember { mutableIntStateOf(0) }
    var speechSheet by remember { mutableStateOf(false) }
    val speechStatus by ReadAloudService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current; val activity = context.activityOrNull()
    val colors = MaterialTheme.colorScheme
    val reducedMotion = LocalReducedMotion.current
    val focusManager = LocalFocusManager.current
    val background = when(settings.theme) { "paper" -> Color(0xFFF4ECD8); "dark" -> Color(0xFF141A16); "light" -> Color(0xFFFAFAF6); else -> colors.surface }
    val foreground = when(settings.theme) { "dark" -> Color(0xFFDDE5DC); "paper", "light" -> Color(0xFF282E27); else -> colors.onSurface }
    SideEffect { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).apply { isAppearanceLightStatusBars = background.luminance() > .5f; isAppearanceLightNavigationBars = background.luminance() > .5f } } }
    DisposableEffect(settings.keepScreenOn, settings.brightness) {
        val old = activity?.window?.attributes?.screenBrightness
        if(settings.keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = settings.brightness }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = old ?: -1f } }
    }
    BackHandler(preferences || toc || search) { preferences = false; toc = false; search = false }
    AsyncContent(listOf(ref, chapterId), load = { c.chapter(ref, chapterId, version > 0) }, refreshKey = version) { (chapter, cached), _ ->
        var prepared by remember(ref, chapterId) { mutableStateOf<List<ReadingParagraph>?>(null) }
        LaunchedEffect(chapter, settings.mode, settings.engines, settings.parallel, settings.traditional) {
            prepared = withContext(Dispatchers.Default) { prepareReadingParagraphs(chapter, settings) }
        }
        val paragraphs = prepared
        if(paragraphs == null) {
            Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = foreground)
                    Text("正在整理正文…", color = foreground)
                }
            }
            return@AsyncContent
        }
        key(ref, chapterId) {
        val position = remember(ref, chapterId) { local.positions[ref.key]?.takeIf { it.chapterId == chapterId } }
        val scroll = rememberLazyListState(position?.index ?: 0, position?.offset ?: 0); val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
        val percent by remember(scroll, paragraphs.size) { derivedStateOf { (((scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0).toFloat() / (paragraphs.size + 1).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100) } }
        val hasFallback = remember(paragraphs) { paragraphs.any { it.fallback } }
        var selected by remember { mutableStateOf<ReadingParagraph?>(null) }; var note by remember { mutableStateOf<ReadingParagraph?>(null) }
        var leaving by remember { mutableStateOf(false) }
        var lastSavedPosition by remember { mutableStateOf<Position?>(null) }
        var finding by remember { mutableStateOf(false) }
        var topOverlayHeight by remember { mutableIntStateOf(0) }
        var bottomOverlayHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val safeTop = WindowInsets.safeDrawing.getTop(density)
        val safeBottom = WindowInsets.safeDrawing.getBottom(density)
        val volumeKeysActive = settings.volumeKeys && !preferences && !search && !toc && !speechSheet && selected == null && note == null
        LaunchedEffect(volumeKeysActive) { if(volumeKeysActive) runCatching { focus.requestFocus() } }
        fun savePosition() {
            if(leaving || scroll.layoutInfo.totalItemsCount == 0 || c.store.state.value.historyPaused) return
            val next = Position(chapterId, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, chapter.title)
            val previous = lastSavedPosition
            if(previous == null || previous.chapterId != next.chapterId || previous.index != next.index || previous.offset != next.offset || previous.title != next.title) {
                c.store.savePosition(ref, next)
                lastSavedPosition = next
            }
        }
        fun openChapter(id: String) {
            if(leaving || id == chapterId) return
            savePosition()
            leaving = true
            c.nav.popBackStack()
            c.read(ref, id)
        }
        fun page(direction: Int) {
            val topCovered = if(menu) (topOverlayHeight - safeTop).coerceAtLeast(0) else 0
            val bottomCovered = (bottomOverlayHeight - safeBottom).coerceAtLeast(0)
            val distance = (scroll.layoutInfo.viewportSize.height - topCovered - bottomCovered).coerceAtLeast(1) * .85f
            scope.launch { if(reducedMotion) scroll.scrollBy(distance * direction) else scroll.animateScrollBy(distance * direction) }
        }
        fun findNext() {
            if(query.isBlank() || finding) return
            val term = query
            val after = scroll.firstVisibleItemIndex - 1
            finding = true
            scope.launch {
                try {
                    val match = withContext(Dispatchers.Default) { findNextReadingParagraph(paragraphs, term, after) }
                    if(match >= 0) {
                        val offset = -(topOverlayHeight - safeTop).coerceAtLeast(0)
                        if(reducedMotion) scroll.scrollToItem(match + 1, offset) else scroll.animateScrollToItem(match + 1, offset)
                    } else c.message("没有找到匹配文字")
                } finally { finding = false }
            }
        }
        BackHandler(!preferences && !toc && !search && !speechSheet && selected == null && note == null) {
            if(!leaving) { savePosition(); leaving = true; c.back() }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        val latestSavePosition by rememberUpdatedState(::savePosition)
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
        LaunchedEffect(chapterId) {
            if(c.session.profile.value != null && !ref.isLocal && !local.historyPaused) {
                try { c.cloudMutation("PUT", "user/read-history/${ref.key}", chapterId, "text/plain") }
                catch(e: CancellationException) { throw e }
                catch(_: Exception) { /* Local progress remains available when history sync fails. */ }
            }
        }
        Box(Modifier.fillMaxSize().background(background)) {
            // Toolbars overlay the page so showing them never remeasures or shifts the reading position.
            LazyColumn(state = scroll, modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing).widthIn(max = settings.width.dp).fillMaxWidth()
                .focusRequester(focus).onPreviewKeyEvent { event ->
                    if(volumeKeysActive && (event.key == Key.VolumeUp || event.key == Key.VolumeDown)) {
                        if(event.type == KeyEventType.KeyDown) page(if(event.key == Key.VolumeDown) 1 else -1)
                        true
                    } else false
                }.focusable(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 88.dp, bottom = if(settings.paged) 156.dp else 104.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item("title", contentType = "title") {
                    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "显示或收起阅读工具栏") { menu = !menu }) {
                        Text(chapter.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = foreground)
                        Spacer(Modifier.height(12.dp))
                        Text(if(ref.isLocal) "本地小说" else (providers[ref.provider].orEmpty() + " · " + if(settings.mode == "jp") "日文原文" else "机翻阅读"), style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .65f))
                        if(hasFallback) Text("部分段落暂无所选译文，显示原文。", style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .7f))
                    }
                }
                itemsIndexed(paragraphs, key = { _, p -> "paragraph-${p.index}" }, contentType = { _, p -> if(p.imageUrl != null || p.localImageId != null) "image" else "paragraph" }) { _, paragraph ->
                    val image = remember(ref, paragraph.imageUrl, paragraph.localImageId) {
                        paragraph.imageUrl ?: paragraph.localImageId?.takeIf { ref.isLocal }?.let { c.store.documentImage(ref.id, it) }
                    }
                    if(image != null) ReaderIllustration(image, foreground) { menu = !menu }
                    else ReaderTextParagraph(paragraph, settings, foreground, { menu = !menu }, { selected = paragraph })
                }
                item("end", contentType = "footer") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        HorizontalDivider(Modifier.padding(vertical = 24.dp)); Text("本章完", color = foreground.copy(alpha = .65f)); Spacer(Modifier.height(20.dp))
                        if(chapter.nextId != null) Button(onClick = { openChapter(chapter.nextId) }, enabled = !leaving) { Text("阅读下一章") }
                        else OutlinedButton(onClick = { toc = true }) { Text("返回目录") }
                    }
                }
            }
            AnimatedVisibility(menu, Modifier.align(Alignment.TopCenter), enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(180)) + slideInVertically(tween(220)) { -it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(140)) + slideOutVertically(tween(180)) { -it }) {
                Column(Modifier.background(background).onSizeChanged { topOverlayHeight = it.height }) {
                    TopAppBar(title = { Text(chapter.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
                        IconButton(onClick = { if(!leaving) { savePosition(); leaving = true; c.back() } }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    }, actions = {
                        if(!ref.isLocal) IconButton(onClick = { version++ }, enabled = !leaving) { Icon(Icons.Outlined.Refresh, "刷新本章译文") }
                        IconButton(onClick = { if(search) focusManager.clearFocus(); search = !search }) { Icon(Icons.Outlined.Search, "搜索本章") }
                        IconButton(onClick = { preferences = true }) { Icon(Icons.Outlined.TextFields, "阅读设置") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = background, titleContentColor = foreground, actionIconContentColor = foreground, navigationIconContentColor = foreground))
                    AnimatedVisibility(search,
                        enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(160)) + expandVertically(tween(220), expandFrom = Alignment.Top),
                        exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(100)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top)
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(query, { query = it }, label = { Text("搜索本章段落") }, singleLine = true, modifier = Modifier.weight(1f), enabled = search)
                            TextButton(onClick = ::findNext, enabled = search && query.isNotBlank() && !finding) { Text(if(finding) "查找中" else "查找") }
                        }
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomOverlayHeight = it.height }) {
                if(settings.paged) Row(Modifier.fillMaxWidth().padding(12.dp).then(if(!menu) Modifier.navigationBarsPadding() else Modifier), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                    FilledTonalButton(onClick = { page(-1) }, enabled = scroll.canScrollBackward) { Text("上一屏") }
                    FilledTonalButton(onClick = { page(1) }, enabled = scroll.canScrollForward) { Text("下一屏") }
                }
                AnimatedVisibility(menu, enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(180)) + slideInVertically(tween(220)) { it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(140)) + slideOutVertically(tween(180)) { it }) {
                    Surface(color = background, contentColor = foreground, tonalElevation = 2.dp) {
                        Column(Modifier.navigationBarsPadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { chapter.prevId?.let(::openChapter) }, enabled = chapter.prevId != null && !leaving) { Icon(Icons.Outlined.SkipPrevious, "上一章") }
                                TextButton(onClick = { toc = true }) { Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(18.dp)); Text(" 目录") }
                                IconButton(onClick = { note = paragraphs.getOrNull((scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)) }, enabled = paragraphs.isNotEmpty()) { Icon(Icons.Outlined.BookmarkAdd, "添加书签或笔记") }
                                IconButton(onClick = { speechSheet = true }) { Icon(Icons.Outlined.VolumeUp, "朗读本章") }
                                IconButton(onClick = { chapter.nextId?.let(::openChapter) }, enabled = chapter.nextId != null && !leaving) { Icon(Icons.Outlined.SkipNext, "下一章") }
                            }
                            Text("${if(cached) "本地内容 · " else ""}$percent% · 点击正文收起工具栏", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .65f))
                        }
                    }
                }
            }
        }
        if(toc) ModalBottomSheet(onDismissRequest = { toc = false }) {
            AsyncContent(ref.key, load = { withContext(Dispatchers.IO) { if(ref.isLocal) c.store.document(ref.id).chapters.map { TocItem(it.title, it.title, it.id) } else c.detail<WebDetail>("novel/${ref.key}").toc } }, modifier = Modifier.fillMaxHeight(.8f)) { list, _ -> TocPanel(c, ref, list, chapterId) { id -> toc = false; openChapter(id) } }
        }
        if(speechSheet) ModalBottomSheet(onDismissRequest = { speechSheet = false }) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("系统朗读", style = MaterialTheme.typography.titleLarge)
                Text(speechStatus.ifBlank { "从当前段落朗读至本章结束。语音由系统提供。" }, style = MaterialTheme.typography.bodyMedium)
                Text("${settings.speechRate}× · ${settings.speechMinutes} 分钟后停止", style = MaterialTheme.typography.labelLarge)
                Button(onClick = {
                    val first = (scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)
                    val originalIndex = paragraphs.getOrNull(first)?.index ?: 0
                    val japanese = settings.speechLanguage == "jp" || (settings.speechLanguage == "auto" && settings.mode.startsWith("jp"))
                    val text = (if(japanese) chapter.paragraphs.drop(originalIndex) else paragraphs.drop(first).mapNotNull { it.parts.firstOrNull { p -> !p.secondary }?.text }).filterNot { it.startsWith("novelia-image:") || it.startsWith("<图片>") }
                    runCatching { ReadAloudService.start(context, text, chapter.title, settings) }.onFailure { c.message(it.friendlyMessage()) }
                }, Modifier.fillMaxWidth()) { Text("从这里开始朗读") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { context.startService(Intent(context, ReadAloudService::class.java).setAction("pause")) }, enabled = speechStatus.startsWith("正在朗读")) { Text("暂停") }
                    TextButton(onClick = { context.startService(Intent(context, ReadAloudService::class.java).setAction("resume")) }, enabled = speechStatus == "朗读已暂停") { Text("继续") }
                    TextButton(onClick = { context.startService(Intent(context, ReadAloudService::class.java).setAction("stop")) }) { Text("停止") }
                    TextButton(onClick = { speechSheet = false; preferences = true }) { Text("设置") }
                }
            }
        }
        selected?.let { paragraph -> ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(20.dp)) {
                SelectionContainer { Text(paragraph.parts.joinToString("\n\n") { it.text }, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { TextButton(onClick = { c.share(paragraph.parts.joinToString("\n\n") { it.text }); selected = null }) { Text("分享段落") }; TextButton(onClick = { note = paragraph; selected = null }) { Text("书签 / 笔记") } }
            }
        } }
        note?.let { paragraph -> var text by remember { mutableStateOf("") }; AlertDialog(onDismissRequest = { note = null }, title = { Text("保存书签或笔记") }, text = { OutlinedTextField(text, { text = it }, label = { Text("笔记（可留空）") }, minLines = 3) }, confirmButton = { TextButton(onClick = { c.store.update { it.copy(notes = it.notes + Note(UUID.randomUUID().toString(), ref.key, chapterId, paragraph.index, paragraph.parts.firstOrNull()?.text.orEmpty(), text)) }; note = null; c.message("已保存到我的笔记") }) { Text("保存") } }, dismissButton = { TextButton(onClick = { note = null }) { Text("取消") } }) }
        }
    }
    if(preferences) ModalBottomSheet(onDismissRequest = { preferences = false }) { ReaderPreferences(settings, local.bookSettings.containsKey(ref.key), { perBook -> c.store.update { it.copy(bookSettings = if(perBook) it.bookSettings + (ref.key to settings) else it.bookSettings - ref.key) } }) { value -> c.store.update { if(it.bookSettings.containsKey(ref.key)) it.copy(bookSettings = it.bookSettings + (ref.key to value)) else it.copy(reader = value) } } }
}

@Composable private fun ReaderTextParagraph(paragraph: ReadingParagraph, settings: ReaderSettings, foreground: Color, onToggleMenu: () -> Unit, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
        onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu,
        onLongClickLabel = "选择段落、分享或添加笔记", onLongClick = onSelect
    ), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paragraph.parts.forEach { part ->
            if(settings.parallel && (part.source == "sakura" || part.source == "gpt" || part.source == "youdao")) Text(part.source.uppercase(), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .65f))
            Text((if(settings.indent) "　　" else "") + part.text,
                fontSize = (if(part.secondary) settings.fontSize - 1 else settings.fontSize).sp,
                lineHeight = (settings.fontSize * settings.lineHeight).sp,
                fontWeight = if(settings.weight) FontWeight.Medium else FontWeight.Normal,
                color = foreground.copy(alpha = if(part.secondary) settings.secondaryAlpha else 1f),
                textDecoration = if(settings.underline && part.secondary) TextDecoration.Underline else null)
        }
    }
}

@Composable private fun ReaderIllustration(model: Any, foreground: Color, onToggleMenu: () -> Unit) {
    val context = LocalContext.current
    val animate = !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()
    val request = remember(context, model, animate) { ImageRequest.Builder(context).data(model).crossfade(if(animate) 180 else 0).build() }
    var loading by remember(model) { mutableStateOf(true) }
    var failed by remember(model) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Reserve a portrait illustration frame before decoding so incoming images cannot shift later paragraphs.
        Box(Modifier.fillMaxWidth().height((maxWidth * 1.35f).coerceIn(180.dp, 900.dp))
            .background(foreground.copy(alpha = .035f))
            .clickable(onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu), contentAlignment = Alignment.Center) {
            AsyncImage(request, "小说插图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onLoading = { loading = true; failed = false }, onSuccess = { loading = false; failed = false }, onError = { loading = false; failed = true })
            if(loading) CircularProgressIndicator(Modifier.size(28.dp), color = foreground.copy(alpha = .65f), strokeWidth = 2.dp)
            if(failed) Text("插图暂时无法加载", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = foreground.copy(alpha = .7f))
        }
    }
}

@Composable fun ReaderPreferences(value: ReaderSettings, perBook: Boolean? = null, onPerBook: (Boolean) -> Unit = {}, onChange: (ReaderSettings) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
        Text("阅读偏好", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        if(perBook != null) TogglePreference("仅应用于这本书", "为当前小说保存独立设置", perBook, onPerBook)
        ChoiceRow("显示语言", listOf("中文", "日文", "中日", "日中"), listOf("zh", "jp", "zh-jp", "jp-zh").indexOf(value.mode)) { onChange(value.copy(mode = listOf("zh", "jp", "zh-jp", "jp-zh")[it])) }
        ChoiceRow("优先译文", listOf("Sakura", "GPT", "有道"), listOf("sakura", "gpt", "youdao").indexOf(value.engines.firstOrNull())) { val engine = listOf("sakura", "gpt", "youdao")[it]; onChange(value.copy(engines = listOf(engine) + value.engines.filterNot { e -> e == engine })) }
        TogglePreference("并列展示译文", "关闭时按优先顺序回退", value.parallel) { onChange(value.copy(parallel = it)) }
        ReaderSlider("字号 ${value.fontSize.toInt()}", value.fontSize, 14f..32f) { onChange(value.copy(fontSize = it)) }
        ReaderSlider("行距 ${"%.1f".format(value.lineHeight)}", value.lineHeight, 1.3f..2.6f) { onChange(value.copy(lineHeight = it)) }
        ReaderSlider("内容宽度 ${value.width.toInt()} dp", value.width, 300f..900f) { onChange(value.copy(width = it)) }
        ReaderSlider("辅文本不透明度 ${(value.secondaryAlpha * 100).toInt()}%", value.secondaryAlpha, .45f..1f) { onChange(value.copy(secondaryAlpha = it)) }
        ChoiceRow("阅读主题", listOf("跟随应用", "纸张", "浅色", "深色"), listOf("system", "paper", "light", "dark").indexOf(value.theme)) { onChange(value.copy(theme = listOf("system", "paper", "light", "dark")[it])) }
        TogglePreference("加粗文字", "中等字重", value.weight) { onChange(value.copy(weight = it)) }
        TogglePreference("首行缩进", "统一为两个全角空格", value.indent) { onChange(value.copy(indent = it)) }
        TogglePreference("繁体显示", "将简体译文转换为繁体", value.traditional) { onChange(value.copy(traditional = it)) }
        TogglePreference("辅文本下划线", "用于双语对照", value.underline) { onChange(value.copy(underline = it)) }
        TogglePreference("屏幕常亮", "仅在阅读器中生效", value.keepScreenOn) { onChange(value.copy(keepScreenOn = it)) }
        TogglePreference("音量键翻页", "音量键控制阅读位置", value.volumeKeys) { onChange(value.copy(volumeKeys = it)) }
        TogglePreference("显示翻页按钮", "单手切换阅读位置", value.paged) { onChange(value.copy(paged = it)) }
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
@Composable fun TogglePreference(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, trailingContent = { Switch(value, onCheckedChange = null) }, modifier = Modifier.toggleable(value = value, role = Role.Switch, onValueChange = onChange)) }
@Composable private fun ReaderSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) { Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Text(label, style = MaterialTheme.typography.labelLarge); Slider(value, onChange, valueRange = range, enabled = enabled) } }
