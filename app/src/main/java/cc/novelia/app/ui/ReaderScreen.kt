@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import cc.novelia.app.reader.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID

@Composable fun ReaderScreen(c: AppController, ref: BookRef, chapterId: String) {
    val local by c.store.state.collectAsStateWithLifecycle()
    val settings = local.bookSettings[ref.key] ?: local.reader
    var menu by remember { mutableStateOf(true) }; var preferences by remember { mutableStateOf(false) }; var toc by remember { mutableStateOf(false) }; var search by remember { mutableStateOf(false) }; var query by rememberSaveable { mutableStateOf("") }; var version by remember { mutableIntStateOf(0) }
    var speechSheet by remember { mutableStateOf(false) }
    val speechStatus by ReadAloudService.status.collectAsStateWithLifecycle()
    val context = LocalContext.current; val activity = context as? Activity
    val colors = MaterialTheme.colorScheme
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
    AsyncContent(listOf(ref, chapterId, version), load = { c.chapter(ref, chapterId, version > 0) }) { (chapter, cached), refresh ->
        val paragraphs = remember(chapter, settings.mode, settings.engines, settings.parallel) { projectParagraphs(chapter, settings) }
        val position = remember(ref, chapterId) { local.positions[ref.key]?.takeIf { it.chapterId == chapterId } }
        val scroll = rememberLazyListState(position?.index ?: 0, position?.offset ?: 0); val scope = rememberCoroutineScope(); val focus = remember { FocusRequester() }
        val percent by remember(scroll, paragraphs.size) { derivedStateOf { (((scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0).toFloat() / (paragraphs.size + 1).coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100) } }
        LaunchedEffect(settings.volumeKeys, preferences, search) { if(settings.volumeKeys && !preferences && !search) runCatching { focus.requestFocus() } }
        var selected by remember { mutableStateOf<ReadingParagraph?>(null) }; var note by remember { mutableStateOf<ReadingParagraph?>(null) }
        fun savePosition() { c.store.savePosition(ref, Position(chapterId, scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, chapter.title)) }
        DisposableEffect(ref, chapterId) { onDispose { savePosition() } }
        LaunchedEffect(scroll, chapterId) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.distinctUntilChanged().collect { delay(350); savePosition() } }
        LaunchedEffect(chapterId) {
            if(c.session.profile.value != null && !ref.isLocal && !local.historyPaused) runCatching { c.cloudMutation("PUT", "user/read-history/${ref.key}", chapterId, "text/plain") }
        }
        val transformed: (String) -> String = remember(settings.traditional) {
            if(settings.traditional) { val converter = com.ibm.icu.text.Transliterator.getInstance("Simplified-Traditional"); { text: String -> converter.transliterate(text) } } else { { text: String -> text } }
        }
        Scaffold(containerColor = background, contentColor = foreground, topBar = {
            if(menu) TopAppBar(title = { Text(chapter.title, maxLines = 1, style = MaterialTheme.typography.titleMedium) }, navigationIcon = { IconButton(onClick = { savePosition(); c.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = {
                if(!ref.isLocal) IconButton(onClick = { version++ }) { Icon(Icons.Outlined.Refresh, "刷新本章译文") }
                IconButton(onClick = { search = !search }) { Icon(Icons.Outlined.Search, "搜索本章") }
                IconButton(onClick = { preferences = true }) { Icon(Icons.Outlined.TextFields, "阅读设置") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = background, titleContentColor = foreground, actionIconContentColor = foreground, navigationIconContentColor = foreground))
        }, bottomBar = {
            if(menu) Surface(color = background, contentColor = foreground, tonalElevation = 2.dp) { Column(Modifier.navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { chapter.prevId?.let { savePosition(); c.nav.popBackStack(); c.read(ref, it) } }, enabled = chapter.prevId != null) { Icon(Icons.Outlined.SkipPrevious, "上一章") }
                    TextButton(onClick = { toc = true }) { Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(18.dp)); Text(" 目录") }
                    IconButton(onClick = { val paragraph = paragraphs.getOrNull((scroll.firstVisibleItemIndex - 1).coerceAtLeast(0)); note = paragraph }) { Icon(Icons.Outlined.BookmarkAdd, "添加书签或笔记") }
                    IconButton(onClick = { speechSheet = true }) { Icon(Icons.Outlined.VolumeUp, "朗读本章") }
                    IconButton(onClick = { chapter.nextId?.let { savePosition(); c.nav.popBackStack(); c.read(ref, it) } }, enabled = chapter.nextId != null) { Icon(Icons.Outlined.SkipNext, "下一章") }
                }
                Text("${if(cached) "本地内容 · " else ""}$percent% · 点击正文收起工具栏", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .65f))
            } }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).then(if(!menu) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
                if(search) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索本章段落") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { val after = scroll.firstVisibleItemIndex; val match = paragraphs.indexOfFirst { it.index >= after && it.parts.any { p -> p.text.contains(query, true) } }.takeIf { it >= 0 } ?: paragraphs.indexOfFirst { it.parts.any { p -> p.text.contains(query, true) } }; if(match >= 0) scope.launch { scroll.animateScrollToItem(match + 1) } else c.message("没有找到匹配文字") }) { Text("查找") }
                }
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    val screenHeight = maxHeight
                    LazyColumn(state = scroll, modifier = Modifier.fillMaxHeight().widthIn(max = settings.width.dp).fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                        if(settings.volumeKeys && event.type == KeyEventType.KeyDown && event.key in listOf(Key.VolumeUp, Key.VolumeDown)) { scope.launch { scroll.animateScrollToItem((scroll.firstVisibleItemIndex + if(event.key == Key.VolumeDown) 4 else -4).coerceIn(0, paragraphs.size)) }; true } else false
                    }.focusable(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        item("title") { Column(Modifier.fillMaxWidth().clickable { menu = !menu }) { Text(chapter.title, style = MaterialTheme.typography.headlineMedium, color = foreground); Spacer(Modifier.height(12.dp)); Text(if(ref.isLocal) "本地小说" else (providers[ref.provider].orEmpty() + " · " + if(settings.mode == "jp") "日文原文" else "机翻阅读"), style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .65f)); if(paragraphs.any { it.fallback }) Text("部分段落暂无所选译文，显示原文。", style = MaterialTheme.typography.labelMedium, color = foreground.copy(alpha = .7f)) } }
                        itemsIndexed(paragraphs, key = { _, p -> "paragraph-${p.index}" }) { _, paragraph ->
                            val imageId = paragraph.parts.firstOrNull()?.text?.takeIf { ref.isLocal && it.matches(Regex("novelia-image:[a-f0-9]{64}")) }?.substringAfter(':')
                            if(paragraph.imageUrl != null) AsyncImage(paragraph.imageUrl, "小说插图", Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 900.dp).clickable { menu = !menu }, contentScale = ContentScale.FillWidth)
                            else if(imageId != null) AsyncImage(c.store.documentImage(ref.id, imageId), "小说插图", Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 900.dp).clickable { menu = !menu }, contentScale = ContentScale.FillWidth)
                            else
                            Column(Modifier.fillMaxWidth().pointerInput(paragraph.index) { detectTapGestures(onTap = { menu = !menu }, onLongPress = { selected = paragraph }) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                paragraph.parts.forEach { part ->
                                    if(settings.parallel && part.source in listOf("sakura", "gpt", "youdao")) Text(part.source.uppercase(), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .65f))
                                    val text = part.text.trim().let { if(part.source == "日文" || part.source.startsWith("原文")) it else transformed(it) }
                                    Text((if(settings.indent) "　　" else "") + text, fontSize = (if(part.secondary) settings.fontSize - 1 else settings.fontSize).sp, lineHeight = (settings.fontSize * settings.lineHeight).sp, fontWeight = if(settings.weight) FontWeight.Medium else FontWeight.Normal, color = foreground.copy(alpha = if(part.secondary) settings.secondaryAlpha else 1f), textDecoration = if(settings.underline && part.secondary) TextDecoration.Underline else null)
                                }
                            }
                        }
                        item("end") { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            HorizontalDivider(Modifier.padding(vertical = 24.dp)); Text("本章完", color = foreground.copy(alpha = .65f)); Spacer(Modifier.height(20.dp))
                            if(chapter.nextId != null) Button(onClick = { savePosition(); c.nav.popBackStack(); c.read(ref, chapter.nextId) }) { Text("阅读下一章") } else OutlinedButton(onClick = { toc = true }) { Text("返回目录") }
                        } }
                    }
                    if(settings.paged) Row(Modifier.align(Alignment.BottomCenter).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledTonalButton(onClick = { scope.launch { scroll.animateScrollToItem((scroll.firstVisibleItemIndex - 4).coerceAtLeast(0)) } }) { Text("上一屏") }
                        FilledTonalButton(onClick = { scope.launch { scroll.animateScrollToItem((scroll.firstVisibleItemIndex + 4).coerceAtMost(paragraphs.size)) } }) { Text("下一屏") }
                    }
                }
            }
        }
        if(toc) ModalBottomSheet(onDismissRequest = { toc = false }) {
            AsyncContent(ref.key, load = { if(ref.isLocal) c.store.document(ref.id).chapters.map { TocItem(it.title, it.title, it.id) } else c.detail<WebDetail>("novel/${ref.key}").toc }, modifier = Modifier.fillMaxHeight(.8f)) { list, _ -> TocPanel(c, ref, list, chapterId) { id -> toc = false; savePosition(); c.nav.popBackStack(); c.read(ref, id) } }
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
    if(preferences) ModalBottomSheet(onDismissRequest = { preferences = false }) { ReaderPreferences(settings, local.bookSettings.containsKey(ref.key), { perBook -> c.store.update { it.copy(bookSettings = if(perBook) it.bookSettings + (ref.key to settings) else it.bookSettings - ref.key) } }) { value -> c.store.update { if(it.bookSettings.containsKey(ref.key)) it.copy(bookSettings = it.bookSettings + (ref.key to value)) else it.copy(reader = value) } } }
}

@Composable fun ReaderPreferences(value: ReaderSettings, perBook: Boolean? = null, onPerBook: (Boolean) -> Unit = {}, onChange: (ReaderSettings) -> Unit) {
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
        if(value.brightness >= 0) ReaderSlider("屏幕亮度", value.brightness, .05f..1f) { onChange(value.copy(brightness = it)) }
        ReaderSlider("朗读速度 ${"%.1f".format(value.speechRate)}×", value.speechRate, .5f..2f) { onChange(value.copy(speechRate = it)) }
        ChoiceRow("朗读语言", listOf("随显示模式", "中文", "日文"), listOf("auto", "zh", "jp").indexOf(value.speechLanguage)) { onChange(value.copy(speechLanguage = listOf("auto", "zh", "jp")[it])) }
        ChoiceRow("朗读定时停止", listOf("15 分钟", "30 分钟", "60 分钟"), listOf(15, 30, 60).indexOf(value.speechMinutes)) { onChange(value.copy(speechMinutes = listOf(15, 30, 60)[it])) }
    }
}
@Composable fun TogglePreference(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) { ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, trailingContent = { Switch(value, onChange) }, modifier = Modifier.clickable { onChange(!value) }) }
@Composable private fun ReaderSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) { Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Text(label, style = MaterialTheme.typography.labelLarge); Slider(value, onChange, valueRange = range) } }
