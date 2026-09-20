@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, coil.annotation.ExperimentalCoilApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cc.novelia.app.data.*
import coil.imageLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@SuppressLint("SetJavaScriptEnabled")
@Composable fun LoginScreen(c: AppController) {
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var loading by remember { mutableStateOf(true) }; val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun complete() {
        if(busy) return
        busy = true; scope.launch {
            try { if(c.session.refresh()) { if(c.store.state.value.autoSync) c.session.profile.value?.username?.let { CloudSyncWorker.enqueue(c.app, it) }; finishLoginNavigation(c); c.message("已登录") } else error = "尚未取得登录会话。请在下方完成登录，再点「完成登录」。" }
            catch(e: Exception) { error = e.friendlyMessage() } finally { busy = false }
        }
    }
    val web = remember { WebView(context).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { loading = false }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, e: WebResourceError?) { if(request?.isForMainFrame == true) { error = "认证页面加载失败，请检查网络后重试"; loading = false } }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return if(uri.scheme == "https" && (!request.isForMainFrame || uri.host in setOf("auth.novelia.cc", "n.novelia.cc"))) false else { if(request.isForMainFrame && uri.scheme == "https") c.external(uri.toString()); true }
            }
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(this, "NoveliaAuth", setOf("https://n.novelia.cc")) { _, message, origin, mainFrame, _ ->
            if(mainFrame && origin.scheme == "https" && origin.host == "n.novelia.cc" && message.data == "login_success") post { complete() }
        }
        loadDataWithBaseURL("https://n.novelia.cc", """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:#f7faf5}iframe{position:fixed;inset:0;width:100vw;height:100vh;border:0}</style></head><body><iframe title="Novelia 统一认证" src="https://auth.novelia.cc/?app=n&amp;theme=system"></iframe><script>window.addEventListener('message',function(e){if(e.origin==='https://auth.novelia.cc'&&e.data&&e.data.type==='login_success'&&window.NoveliaAuth){window.NoveliaAuth.postMessage('login_success');}});</script></body></html>""", "text/html", "UTF-8", null)
    } }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy(); c.afterLogin = null } }
    Screen("登录 Novelia", c::back, actions = { TextButton(onClick = ::complete, enabled = !busy) { Text(if(busy) "验证中…" else "完成登录") } }) { padding -> Column(Modifier.padding(padding)) {
        Text("使用原站统一账号登录、注册或找回密码。密码由认证网站直接处理。", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(loading || busy) { if(appReducedMotion()) Text(if(busy) "验证中…" else "正在加载认证页面…", Modifier.padding(horizontal = 20.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { error = null; loading = true; web.reload() }) { Text("重新加载认证页") } }
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
    } }
}
@Composable fun ProfileScreen(c: AppController) {
    val profile by c.session.profile.collectAsStateWithLifecycle(); val state by c.store.state.collectAsStateWithLifecycle(); var logout by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val companionVisible by remember { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.any { it.key == "profile-card" }
    } }
    Screen("我的") { padding -> AppLazyColumn(Modifier.padding(padding), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "profile-card") { Card(Modifier.fillMaxWidth().padding(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MidoriCompanion(visible = companionVisible)
                Text(profile?.username ?: "你好，阅读者", style = MaterialTheme.typography.headlineMedium)
                Text(if(profile == null) "在此设备阅读，也可以连接原站账号。" else "${mapOf("admin" to "管理员", "member" to "普通成员", "trusted" to "可信成员", "restricted" to "受限账号", "banned" to "被封禁账号")[profile?.role] ?: profile?.role} · 注册于 ${displayDate(profile!!.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                if(profile == null) Button(onClick = { c.go("login") }) { Text("登录 / 注册") } else TextButton(onClick = { logout = true }) { Text("退出登录") }
            }
        } }
        if(profile != null) item { MetaParagraph("账号权限", "社区发布：${if(profile!!.canPost) "可用" else "受限"}\n书籍编辑：${if(profile!!.canEdit) "可用" else "需要符合原站角色与注册时间要求"}\n操作最终由原站服务器校验。") }
        item { MenuRow("下载管理", "查看进度、导出与离线阅读", Icons.Outlined.Download, { c.go("downloads") }) }
        item { MenuRow("书签与笔记", "${state.notes.size} 条阅读记录", Icons.Outlined.EditNote, { c.go("notes") }) }
        item { MenuRow("文件工具", "EPUB 转 TXT、图片压缩、文本换行整理与片假名统计", Icons.Outlined.Handyman, { c.go("tools") }) }
        item { MenuRow("阅读与外观", "字号、主题、动效与朗读", Icons.Outlined.Tune, { c.go("settings") }) }
        item { MenuRow("屏蔽管理", "管理作品和标签屏蔽", Icons.Outlined.Block, { c.go("blocked") }) }
        val pending = state.pending.count { it.account == profile?.username }
        item { MenuRow("同步状态", if(pending > 0) "$pending 项待处理 · 查看原因与重试" else "自动同步、状态与重试", Icons.Outlined.Sync, { c.go("sync") }) }
        item { MenuRow("阅读资料备份", "书架、进度、笔记、本地小说与标签词典", Icons.Outlined.Backup, { c.go("backup") }) }
        item { MenuRow("书架更新", "新增章节、译文与分卷", Icons.Outlined.NewReleases, { c.go("updates") }) }
        item { MenuRow("帮助与关于", "使用说明、版本与反馈", Icons.Outlined.Info, { c.go("about") }) }
    } }
    if(logout) ConfirmDialog("退出当前账号？", "本地小说、下载和笔记仍保留在此设备。云端操作需要重新登录。", { logout = false }) { c.action("已退出登录") { c.session.logout() } }
}
@Composable fun SettingsScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var reader by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }; var size by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(c) { size = withContext(Dispatchers.IO) { c.store.cacheSize() + (c.app.imageLoader.diskCache?.size ?: 0L) } }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> c.message(if(granted) "已允许通知" else "可在系统设置中开启通知") }
    val exportSettings = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { c.action("设置已导出") { val s = c.store.state.value; val backup = SettingsBackup(reader = s.reader, theme = s.theme, reducedMotion = s.reducedMotion, blockedBooks = s.blockedBooks, blockedTags = s.blockedTags, blockedUsers = s.blockedUsers, hideNovelComments = s.hideNovelComments, wifiOnly = s.wifiOnly, autoCollapseCloudFilters = s.autoCollapseCloudFilters); withContext(Dispatchers.IO) { c.app.contentResolver.openOutputStream(it)?.use { output -> output.write(appJson.encodeToString(backup).toByteArray()) } ?: error("无法写入文件") } } } }
    val importSettings = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { c.action("设置已导入") { val backup = withContext(Dispatchers.IO) { appJson.decodeFromString<SettingsBackup>(readDocument(c, it).second.toString(Charsets.UTF_8)) }; require(backup.version == 1 && backup.theme in listOf("system", "light", "dark") && backup.reader.fontSize in 14f..32f && backup.reader.lineHeight in 1.3f..2.6f && backup.reader.width in 300f..900f && backup.reader.engines.toSet() == setOf("sakura", "gpt", "youdao")); c.store.update { s -> s.copy(reader = backup.reader, theme = backup.theme, reducedMotion = backup.reducedMotion, blockedBooks = backup.blockedBooks, blockedTags = backup.blockedTags, blockedUsers = backup.blockedUsers, hideNovelComments = backup.hideNovelComments, wifiOnly = backup.wifiOnly, autoCollapseCloudFilters = backup.autoCollapseCloudFilters) } } } }
    Screen("阅读与外观", c::back) { padding -> AppLazyColumn(Modifier.padding(padding)) {
        item { TogglePreference("电子纸阅读模式", "全应用按屏翻动，关闭滚动惯性和动画，使用按钮调整分卷顺序", state.reader.eInkMode) { value -> c.store.update { it.copy(reader = it.reader.withEInkMode(value)) } } }
        item { ChoiceRow("应用主题", listOf("跟随系统", "浅色", "深色"), listOf("system", "light", "dark").indexOf(state.theme)) { index -> c.store.update { it.copy(theme = listOf("system", "light", "dark")[index]) } } }
        item { TogglePreference("减少动态效果", "", state.reducedMotion) { value -> c.store.update { it.copy(reducedMotion = value) } } }
        item { TogglePreference("滚动时自动收起筛选", "云端收藏和网络小说辅助搜索：向下浏览列表时收起，点击摘要展开", state.autoCollapseCloudFilters) { value -> c.store.update { it.copy(autoCollapseCloudFilters = value) } } }
        item { MenuRow("默认阅读偏好", "调整所有小说的阅读体验", Icons.Outlined.TextFields, { reader = true }) }
        item { MenuRow("朗读通知", "允许在通知栏控制朗读", Icons.Outlined.Notifications, { if(Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) else c.message("当前系统无需申请通知权限") }) }
        item { TogglePreference("仅在 Wi-Fi 下载", "新建下载任务等待非计费网络", state.wifiOnly) { value -> c.store.update { it.copy(wifiOnly = value) } } }
        item { TogglePreference("书架更新提醒", "约每 6 小时检查，系统调度可能延后", state.updateNotifications) { value -> c.store.update { it.copy(updateNotifications = value) }; UpdateWorker.schedule(c.app, value); if(value && Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
        item { TogglePreference("隐藏小说评论", "论坛文章评论仍然显示", state.hideNovelComments) { value -> c.store.update { it.copy(hideNovelComments = value) } } }
        item { MenuRow("阅读与图片缓存", if(clearing) "正在清理…" else size?.let { "已使用 ${"%.1f".format(it / 1024.0 / 1024.0)} MB · 点击清理" } ?: "正在计算缓存大小…", Icons.Outlined.Storage, { if (!clearing) clear = true }) }
        item { MenuRow("导出普通设置", "阅读偏好、外观与屏蔽名单，不含账号会话", Icons.Outlined.IosShare, { exportSettings.launch("novelia-settings.json") }) }
        item { MenuRow("备份与恢复阅读资料", "迁移书架、阅读进度、笔记和本地小说", Icons.Outlined.Backup, { c.go("backup") }) }
        item { MenuRow("导入普通设置", "从 Novelia 设置文件恢复偏好", Icons.Outlined.FileOpen, { importSettings.launch(arrayOf("application/json", "*/*")) }) }
        item { MetaParagraph("本地数据", "小说文件、书签和偏好保存在此设备。系统文件选择器负责导入和导出，无需申请全部存储空间权限。卸载应用会删除这些数据，请先导出需要保留的文件。") }
    } }
    if(reader) AppSheet(onDismissRequest = { reader = false }) { ReaderPreferences(state.reader) { value -> c.store.update { it.copy(reader = value) } } }
    if(clear) ConfirmDialog("清理阅读与图片缓存？", "已缓存的网络章节、详情和图片会被删除，之后需要联网加载。本地导入的小说和下载文件不受影响。", { clear = false }) {
        clearing = true
        c.action("缓存已清理") {
            try {
                size = withContext(Dispatchers.IO) {
                    c.store.clearCache()
                    c.app.imageLoader.memoryCache?.clear()
                    c.app.imageLoader.diskCache?.clear()
                    c.store.cacheSize() + (c.app.imageLoader.diskCache?.size ?: 0L)
                }
            } finally { clearing = false }
        }
    }
}
@Composable fun NotesScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var editing by remember { mutableStateOf<Note?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var bookKey by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingBook by remember { mutableStateOf(false) }
    val settledQuery = rememberDebouncedQuery(query)
    val allNotes = remember(state.notes, state.books, state.positions) { presentNotes(state) }
    val bookChoices = remember(allNotes) { allNotes.distinctBy { it.note.key }.sortedBy { it.bookTitle } }
    val bookCounts = remember(allNotes) { allNotes.groupingBy { it.note.key }.eachCount() }
    val notes = remember(state.notes, state.books, state.positions, settledQuery, bookKey) { presentNotes(state, settledQuery, bookKey) }
    Screen("书签与笔记", c::back) { padding -> AppLazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "note-search") {
            OutlinedTextField(query, { query = it }, label = { Text("搜索书名、章节、摘录或笔记") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().testTag("notes-search"))
        }
        item(key = "note-filter") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = bookKey != null, onClick = { choosingBook = true }, label = { Text(bookChoices.firstOrNull { it.note.key == bookKey }?.bookTitle ?: "全部书籍", maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.FilterList, null, Modifier.size(18.dp)) })
                if(bookKey != null || query.isNotBlank()) TextButton(onClick = { bookKey = null; query = "" }) { Text("清空筛选") }
                Text("${notes.size} 条", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        if(state.notes.isEmpty()) item { EmptyState("记下喜欢的句子", "在阅读器长按段落，或点击书签按钮保存。", Icons.Outlined.EditNote) }
        else if(notes.isEmpty()) item { EmptyState("没有匹配的笔记", "调整关键词，或查看全部书籍的笔记。", Icons.Outlined.SearchOff, "清空筛选", { query = ""; bookKey = null }) }
        items(notes, key = { it.note.id }) { item -> val note = item.note; Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.bookTitle, style = MaterialTheme.typography.titleSmall)
            Text("${item.chapterTitle} · 第 ${note.paragraph + 1} 段", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(note.quote, maxLines = 5, style = MaterialTheme.typography.bodyMedium); if(note.text.isNotBlank()) Text(note.text, color = MaterialTheme.colorScheme.primary)
            FlowRow {
                TextButton(onClick = { val ref = BookRef.fromKey(note.key); c.store.savePosition(ref, Position(note.chapterId, note.paragraph + 1)); c.read(ref, note.chapterId) }) { Text("回到原文") }
                TextButton(onClick = { editing = note }) { Text("编辑") }
                TextButton(onClick = { c.share("${item.bookTitle} · ${item.chapterTitle}\n\n${note.quote}" + if(note.text.isNotBlank()) "\n\n${note.text}" else "") }) { Text("分享") }
                TextButton(onClick = {
                    c.store.update { it.copy(notes = it.notes.filterNot { n -> n.id == note.id }) }
                    c.action {
                        if(c.snackbar.showSnackbar("笔记已删除", actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                            c.store.update { if(it.notes.any { n -> n.id == note.id }) it else it.copy(notes = it.notes + note) }
                        }
                    }
                }) { Text("删除") }
            }
        } } }
    } }
    if(choosingBook) AppSheet(onDismissRequest = { choosingBook = false }) {
        AppLazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("按书籍查看", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge) }
            item { MenuRow("全部书籍", "${allNotes.size} 条笔记", Icons.Outlined.LibraryBooks, { bookKey = null; choosingBook = false }) }
            items(bookChoices, key = { it.note.key }) { item -> MenuRow(item.bookTitle, "${bookCounts[item.note.key] ?: 0} 条笔记", Icons.Outlined.MenuBook, { bookKey = item.note.key; choosingBook = false }) }
        }
    }
    editing?.let { note -> TextPrompt("编辑笔记", "内容", note.text, { editing = null }) { value -> c.store.update { it.copy(notes = it.notes.map { n -> if(n.id == note.id) n.copy(text = value) else n }) } } }
}
@Composable fun BlockedScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); var add by remember { mutableStateOf(false) }
    var addUser by remember { mutableStateOf(false) }
    Screen("屏蔽管理", c::back, actions = { IconButton(onClick = { add = true }) { Icon(Icons.Outlined.Add, "屏蔽标签") } }) { padding -> AppLazyColumn(Modifier.padding(padding)) {
        item { MetaParagraph("本地屏蔽", "只影响此设备的发现列表；不会更改原站收藏。") }
        item { SectionTitle("用户", "添加用户") { addUser = true } }
        items(state.blockedUsers.toList()) { user -> MenuRow(user, "点击取消屏蔽", Icons.Outlined.PersonOff, { c.store.update { it.copy(blockedUsers = it.blockedUsers - user) } }) }
        item { SectionTitle("标签") }
        items(state.blockedTags.toList()) { tag -> MenuRow(tag, "点击取消屏蔽", Icons.Outlined.Label, { c.store.update { it.copy(blockedTags = it.blockedTags - tag) } }) }
        item { SectionTitle("作品") }
        items(state.blockedBooks.toList()) { key -> MenuRow(state.books.find { it.book.ref.key == key }?.book?.title ?: key, "点击取消屏蔽", Icons.Outlined.Book, { c.store.update { it.copy(blockedBooks = it.blockedBooks - key) } }) }
        if(state.blockedTags.isEmpty() && state.blockedBooks.isEmpty()) item { EmptyState("没有屏蔽内容", "可以在作品详情屏蔽小说，或添加不想看到的标签。", Icons.Outlined.FilterAlt) }
    } }
    if(add) TextPrompt("屏蔽标签", "与原站标签完全一致", onDismiss = { add = false }) { tag -> c.store.update { it.copy(blockedTags = it.blockedTags + tag) } }
    if(addUser) TextPrompt("屏蔽用户", "输入原站用户名", onDismiss = { addUser = false }) { user -> c.store.update { it.copy(blockedUsers = it.blockedUsers + user) } }
}
@Composable fun AboutScreen(c: AppController) {
    Screen("帮助与关于", c::back) { padding -> AppLazyColumn(Modifier.padding(padding)) {
        item { AboutIdentity() }
        item { MenuRow("原站使用教程", "账号规则、检索语法与资源说明", Icons.Outlined.HelpOutline, { c.go("article/64f3d63f794cbb1321145c07") }) }
        item { MenuRow("反馈与建议", "在 GitHub 报告客户端问题", Icons.Outlined.Forum, { c.external("https://github.com/nobYoQ/novelia/issues") }) }
        item { MenuRow("下载新版本", "查看 GitHub 发行版与更新说明", Icons.Outlined.Download, { c.external("https://github.com/nobYoQ/novelia/releases") }) }
        item { MenuRow("项目源码", "查看源码与贡献指南", Icons.Outlined.Code, { c.external("https://github.com/nobYoQ/novelia") }) }
        item { MenuRow("开源许可证", "离线查看项目许可与第三方声明", Icons.Outlined.Description, { c.go("licenses") }) }
        item { MenuRow("访问原站", "n.novelia.cc", Icons.Outlined.OpenInNew, { c.external("https://n.novelia.cc") }) }
        item { MetaParagraph("关于此版本", "使用 Kotlin、Jetpack Compose 和 Material 3 构建。保留原站绿色主题，提供原生阅读、本地文件与社区入口。此版本不包含翻译中心或生成译文的任务。原站内容与账号权限由 Novelia 服务提供。") }
        item { MetaParagraph("源码授权", "项目原创代码采用 GPL-3.0。第三方组件与贴纸保留各自的许可和权利，详情见开源许可证。") }
    } }
}
