@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.importDocumentUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun importDocument(c: AppController, uri: Uri) = withContext(Dispatchers.IO) {
    val result = importDocumentUri(c.store, uri)
    if(!result.imported) {
        val existing = c.store.state.value.books.firstOrNull { it.book.ref == result.ref }
        c.message(existing?.let { "「${it.book.title}」已在书架中" } ?: "这份文件已在书架中")
    }
    result.imported
}
fun readDocument(c: AppController, uri: Uri): Pair<String, ByteArray> {
    val resolver = c.app.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "导入文档.txt"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = input.read(buffer); if(n < 0) break; require(output.size() + n <= DocumentTools.MAX_INPUT) { "文件超过 64 MB" }; output.write(buffer, 0, n) }
        output.toByteArray()
    } ?: error("无法读取文件")
    return name to bytes
}
@Composable fun ShelfScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }; var query by rememberSaveable { mutableStateOf("") }; var folder by rememberSaveable { mutableStateOf("全部") }; var sort by rememberSaveable { mutableIntStateOf(0) }
    var createFolder by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf<SavedBook?>(null) }; var managing by remember { mutableStateOf(false) }; var selection by remember { mutableStateOf(setOf<String>()) }; var bulkMove by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf(false) }; var deleteFolder by remember { mutableStateOf(false) }; var localExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var queueingDownloads by remember { mutableStateOf(false) }
    var volumeManager by remember { mutableStateOf<SavedBook?>(null) }
    var volumeParentPicker by remember { mutableStateOf<SavedBook?>(null) }
    val sourceExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pendingId = localExportId
        localExportId = null
        if(uri != null) c.action("原文件已导出") {
            val id = requireNotNull(pendingId) { "待导出的小说已不存在，请重新选择" }
            withContext(Dispatchers.IO) {
                val doc = c.store.document(id)
                val source = c.store.documentSource(id, doc.format)
                c.app.contentResolver.openOutputStream(uri)?.use { output ->
                    if(source.exists()) source.inputStream().use { it.copyTo(output) }
                    else output.bufferedWriter(Charsets.UTF_8).use { writer ->
                        doc.chapters.forEachIndexed { index, chapter ->
                            if(index > 0) writer.write("\n\n")
                            chapter.paragraphs.forEachIndexed { paragraphIndex, text ->
                                if(paragraphIndex > 0) writer.write("\n\n")
                                writer.write(text)
                            }
                        }
                    }
                } ?: error("无法写入文件")
            }
        }
    }
    var importing by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) c.action {
            importing = true
            try {
                var imported = 0
                uris.forEach { if(importDocument(c, it)) imported++ }
                if(imported > 0) c.celebrate("已导入 $imported 本小说", MidoriSticker.Approve)
            } finally { importing = false }
        }
    }
    val recent = remember(state.books, state.positions) {
        state.books.asSequence().filter { state.positions.containsKey(it.book.ref.key) }.maxByOrNull { state.positions.getValue(it.book.ref.key).updatedAt }
    }
    val settledQuery = rememberDebouncedQuery(query)
    var collapsedSearchGroups by remember(settledQuery) { mutableStateOf(setOf<String>()) }
    val reducedMotion = LocalReducedMotion.current
    val tabState = rememberSaveableStateHolder()
    val groups = remember(state.books, state.positions, tab, folder, settledQuery, sort) {
        state.shelfGroups(localOnly = tab == 1, folder = folder, query = settledQuery, sort = sort)
    }
    val books = remember(groups) { groups.flatMap { listOf(it.saved) + it.volumes } }
    val rows = remember(groups, collapsedSearchGroups, settledQuery) {
        buildList {
            groups.forEach { group ->
                val expanded = if(settledQuery.isNotBlank()) group.saved.book.ref.key !in collapsedSearchGroups else group.saved.volumesExpanded
                add(ShelfRowItem(group.saved, volumeCount = group.volumes.size, expanded = expanded))
                if(expanded) group.volumes.forEach { add(ShelfRowItem(it, parent = group.saved)) }
            }
        }
    }
    val downloadableSelection = remember(books, selection) { books.filter { it.book.ref.key in selection && !it.book.ref.isLocal && !it.book.ref.isWenku } }
    Screen("书架", actions = {
        IconButton(onClick = { c.go("updates") }) { Icon(Icons.Outlined.NewReleases, "查看书架更新") }
        IconButton(onClick = { UpdateWorker.checkNow(c.app); c.message("已开始检查书架更新") }) { Icon(Icons.Outlined.Sync, "检查更新") }
        IconButton(onClick = { c.go("history") }) { Icon(Icons.Outlined.History, "阅读历史") }
        IconButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !importing) { Icon(Icons.Outlined.FileOpen, "导入本地文件") }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(tab) { listOf("我的收藏", "本地文件", "云端收藏").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
            if(importing) { if(LocalEInkMode.current) Text("正在导入…", Modifier.padding(horizontal = 20.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
            MotionContent(tab, Modifier.weight(1f).fillMaxWidth(), animateInitial = false) {
                tabState.SaveableStateProvider(tab) {
                    if(tab == 2) CloudShelf(c) else {
                        val listState = rememberLazyListState()
                        val canReorder = tab == 0 && !managing && query.isBlank() && settledQuery.isBlank()
                        val reorder = rememberVolumeReorderState(listState, rows, canReorder) { parent, keys ->
                            c.store.update { current ->
                                // A download can finish during a gesture. Keep newly mounted volumes at the end.
                                val siblings = current.books.filter { it.book.ref.isLocal && it.parentWenkuKey == parent }.map { it.book.ref.key }
                                if(current.books.none { it.book.ref.key == parent && it.book.ref.isWenku }) current
                                else current.withWenkuVolumeOrder(parent, keys.filter { it in siblings } + siblings.filter { it !in keys })
                            }
                        }
                        AppLazyColumn(listModifier = Modifier.testTag("shelf-books"), state = listState) {
                            if(recent != null && tab == 0 && query.isBlank() && folder == "全部") item(key = "continue-reading", contentType = "hero") {
                                val position = state.positions.getValue(recent.book.ref.key)
                                Card(onClick = { c.read(recent.book.ref, position.chapterId) }, modifier = Modifier.fillMaxWidth().padding(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("继续上次的故事", style = MaterialTheme.typography.labelLarge)
                                        Text(recent.book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                                        Text(position.title.ifBlank { "已记录阅读位置" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.PlayArrow, null); Text("继续阅读", style = MaterialTheme.typography.labelLarge) }
                                    }
                                }
                            }
                            item(key = "search", contentType = "search") { OutlinedTextField(query, { query = it }, label = { Text("搜索书架") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true, shape = MaterialTheme.shapes.extraLarge) }
                            item(key = "folders", contentType = "folders") { Row(Modifier.appHorizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf("全部") + state.folders).forEach { value -> FilterChip(folder == value, { folder = value }, label = { Text(value) }) }
                                AssistChip(onClick = { createFolder = true }, label = { Text("新建收藏夹") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) })
                            } }
                            item(key = "sort", contentType = "controls") { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${groups.size} 本" + if(books.size > groups.size) " · ${books.size - groups.size} 分卷" else "", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { sort = (sort + 1) % 3 }) { Text(listOf("最近阅读", "添加时间", "书名排序")[sort]) }
                                IconButton(onClick = { managing = !managing; selection = emptySet() }) { MotionContent(managing, animateInitial = false) { Icon(if(managing) Icons.Outlined.Check else Icons.Outlined.Checklist, if(managing) "完成整理" else "批量整理") } }
                            } }
                            item(key = "folder-actions", contentType = "controls") {
                                ShelfControlReveal(folder != "全部" && folder != "默认收藏") {
                                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                                        TextButton(onClick = { renameFolder = true }, enabled = folder != "全部" && folder != "默认收藏") { Text("重命名收藏夹") }
                                        TextButton(onClick = { deleteFolder = true }, enabled = folder != "全部" && folder != "默认收藏") { Text("删除收藏夹") }
                                    }
                                }
                            }
                            item(key = "bulk-actions", contentType = "controls") { ShelfControlReveal(managing) { FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { selection = books.map { it.book.ref.key }.toSet() }, enabled = managing) { Text("全选") }
                                FilledTonalButton(onClick = { bulkMove = true }, enabled = managing && selection.isNotEmpty()) { Text("移动 ${selection.size} 本") }
                                TextButton(onClick = {
                                    val chosen = downloadableSelection
                                    queueingDownloads = true
                                    c.action {
                                        try {
                                            val invalidFilenameChars = Regex("[\\/\\\\:*?\"<>|]")
                                            val reader = state.reader
                                            chosen.forEach { saved ->
                                                val id = java.util.UUID.randomUUID().toString()
                                                val filename = saved.book.title.replace(invalidFilenameChars, "_").take(120) + ".epub"
                                                val url = c.api.downloadUrl(saved.book.ref, null, reader.mode, reader.engines, reader.parallel, "epub", filename)
                                                cc.novelia.app.files.DownloadWorker.enqueue(c.app, DownloadEntry(id, saved.book.title, "$id-$filename", url))
                                            }
                                            c.message("已加入 ${chosen.size} 本网络小说到下载列表")
                                            managing = false
                                            selection = emptySet()
                                        } finally { queueingDownloads = false }
                                    }
                                }, enabled = managing && !queueingDownloads && downloadableSelection.isNotEmpty()) { Text(if(queueingDownloads) "正在加入…" else "下载") }
                            } } }
                            if(books.isEmpty()) item { EmptyState(if(tab == 1) "把故事装进口袋" else "书架等你来填满", if(tab == 1) "支持 EPUB、TXT 和 SRT，导入后即可离线阅读。" else "去发现喜欢的小说，或导入你已有的文件。", action = if(tab == 1) "导入文件" else "去发现", onAction = { if(tab == 1) importer.launch(arrayOf("*/*")) else c.go("discover") }, sticker = MidoriSticker.Welcome) }
                            items(reorder.rows, key = { it.saved.book.ref.key }, contentType = { if(it.parent == null) "book" else "volume" }) { row ->
                                val saved = row.saved
                                val dragging = reorder.draggedKey == saved.book.ref.key
                                val selectionColor = animateColorAsState(
                                    if(managing && saved.book.ref.key in selection) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f) else Color.Transparent,
                                    animationSpec = tween(if(reducedMotion) 0 else 180), label = "shelf-selection"
                                )
                                val itemMotion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(180), placementSpec = if(dragging) null else tween(220), fadeOutSpec = tween(120))
                                val onOpen = { if(managing) selection = if(saved.book.ref.key in selection) selection - saved.book.ref.key else selection + saved.book.ref.key else c.book(saved.book.ref) }
                                val trailing: @Composable () -> Unit = {
                                    if(managing) Checkbox(saved.book.ref.key in selection, { checked -> selection = if(checked) selection + saved.book.ref.key else selection - saved.book.ref.key }) else IconButton(onClick = { selected = saved }) { Icon(Icons.Outlined.MoreVert, "管理 ${saved.book.title}") }
                                }
                                if(row.parent != null) MountedVolumeRow(saved, state.positions[saved.book.ref.key],
                                    itemMotion.zIndex(if(dragging) 1f else 0f).graphicsLayer {
                                        translationY = if(dragging) reorder.offset else 0f
                                        shadowElevation = if(dragging && !reducedMotion) 4.dp.toPx() else 0f
                                    }.drawBehind { drawRect(selectionColor.value) }, onOpen, trailing,
                                    dragHandle = if(canReorder && reorder.siblings(saved.book.ref.key).size > 1) {
                                        { VolumeDragHandle(reorder, saved.book.ref.key, saved.book.title) }
                                    } else null)
                                else Column(itemMotion.testTag("shelf-book-${saved.book.ref.key}").drawBehind { drawRect(selectionColor.value) }) {
                                    BookRow(if(saved.hasUpdates) saved.book.copy(subtitle = state.bookUpdates[saved.book.ref.key]?.summary?.ifBlank { null } ?: "有更新 · ${saved.book.subtitle}") else saved.book, onOpen, trailing = trailing)
                                    if(saved.book.ref.isWenku && !managing) {
                                        val rotation by animateFloatAsState(if(row.expanded) 180f else 0f, tween(if(reducedMotion) 0 else 220), label = "wenku-volume-disclosure")
                                        TextButton(onClick = {
                                            if(row.volumeCount == 0) volumeManager = saved
                                            else if(settledQuery.isNotBlank()) collapsedSearchGroups = if(row.expanded) collapsedSearchGroups + saved.book.ref.key else collapsedSearchGroups - saved.book.ref.key
                                            else c.store.update { it.copy(books = it.books.map { book -> if(book.book.ref == saved.book.ref) book.copy(volumesExpanded = !row.expanded) else book }) }
                                        }, modifier = Modifier.padding(start = 20.dp).testTag("wenku-volumes-${saved.book.ref.key}")
                                            .semantics { stateDescription = if(row.volumeCount == 0) "未挂载分卷" else if(row.expanded) "已展开" else "已折叠" }) {
                                            Icon(if(row.volumeCount == 0) Icons.Outlined.Add else Icons.Outlined.ExpandMore, null,
                                                if(row.volumeCount == 0) Modifier else Modifier.rotate(rotation))
                                            Spacer(Modifier.width(8.dp))
                                            Text(if(row.volumeCount == 0) "挂载分卷" else "${if(row.expanded) "收起" else "展开"} ${row.volumeCount} 个分卷")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(createFolder) TextPrompt("新建收藏夹", "名称", onDismiss = { createFolder = false }) { name -> c.store.update { it.copy(folders = (it.folders + name).distinct()) } }
    if(renameFolder) TextPrompt("重命名收藏夹", "名称", folder, { renameFolder = false }) { name -> val previous = folder; c.store.update { it.copy(folders = (it.folders.map { f -> if(f == previous) name else f }).distinct(), books = it.books.map { b -> if(b.folder == previous) b.copy(folder = name) else b }) }; folder = name }
    if(deleteFolder) ConfirmDialog("删除收藏夹？", "其中的书籍会移入默认收藏，文件不会删除。", { deleteFolder = false }) { val previous = folder; c.store.update { it.copy(folders = it.folders - previous, books = it.books.map { b -> if(b.folder == previous) b.copy(folder = "默认收藏") else b }) }; folder = "全部" }
    if(bulkMove) AlertDialog(onDismissRequest = { bulkMove = false }, title = { Text("移入收藏夹") }, text = { Column {
        if(state.books.any { it.book.ref.key in selection && it.parentWenkuKey != null && it.parentWenkuKey !in selection }) Text("单独移动分卷会取消其挂载；同时移动所属文库可保留挂载。", style = MaterialTheme.typography.bodySmall)
        state.folders.forEach { target -> TextButton(onClick = { c.store.update { it.moveShelfBooks(selection, target) }; bulkMove = false; managing = false; selection = emptySet() }) { Text(target) } }
    } }, confirmButton = {})
    selected?.let { saved -> AppSheet(onDismissRequest = { selected = null }) { AppScrollColumn(modifier = Modifier.navigationBarsPadding(), contentModifier = Modifier.padding(bottom = 28.dp)) {
        Text(saved.book.title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2)
        if(saved.hasUpdates) MenuRow("标记更新已读", "清除本书的更新提示", Icons.Outlined.DoneAll, { c.store.update { it.copy(books = it.books.map { b -> if(b.book.ref == saved.book.ref) b.copy(hasUpdates = false) else b }, bookUpdates = it.bookUpdates - saved.book.ref.key) }; selected = null })
        MenuRow(if(saved.pinned) "取消置顶" else "置顶", "在书架顶部显示", Icons.Outlined.PushPin, { c.store.update { it.copy(books = it.books.map { b -> if(b.book.ref == saved.book.ref) b.copy(pinned = !b.pinned) else b }) }; selected = null })
        ChoiceRow("阅读状态", listOf("在读", "想读", "读完"), listOf("在读", "想读", "读完").indexOf(saved.status)) { status -> c.store.update { it.copy(books = it.books.map { b -> if(b.book.ref == saved.book.ref) b.copy(status = listOf("在读", "想读", "读完")[status]) else b }) }; selected = null }
        val parent = state.books.firstOrNull { it.book.ref.isWenku && it.book.ref.key == saved.parentWenkuKey }
        if(parent == null) ChoiceRow("收藏夹", state.folders, state.folders.indexOf(saved.folder)) { index -> c.store.saveBook(saved.book, state.folders[index]); selected = null }
        else Text("所属收藏夹：${parent.folder}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if(saved.book.ref.isWenku) MenuRow("管理挂载分卷", "选择已导入的分卷，在本书下方展开阅读", Icons.Outlined.LibraryAdd, { selected = null; volumeManager = saved })
        if(saved.book.ref.isLocal) MenuRow(if(parent == null) "挂载到文库小说" else "更换或取消挂载", parent?.let { "当前挂载：${it.book.title}" } ?: "归入指定的文库收藏", Icons.Outlined.DriveFileMove, { selected = null; volumeParentPicker = saved })
        if(saved.book.ref.isLocal) MenuRow("本地术语表", "维护此文件的专有名词", Icons.Outlined.Translate, { selected = null; c.go("glossary/${saved.book.ref.key}") })
        if(saved.book.ref.isLocal) MenuRow("导出原文件", "保留导入时的格式与内容", Icons.Outlined.IosShare, { c.action { val doc = withContext(Dispatchers.IO) { c.store.document(saved.book.ref.id) }; localExportId = saved.book.ref.id; selected = null; sourceExporter.launch("${doc.name}.${doc.format}") } })
        MenuRow("移出书架", "不会删除下载文件或阅读记录", Icons.Outlined.RemoveCircleOutline, { c.store.removeBook(saved.book.ref); selected = null })
        if(saved.book.ref.isLocal) MenuRow("删除本地小说", "删除此文件的导入副本", Icons.Outlined.DeleteOutline, { selected = null; c.action { withContext(Dispatchers.IO) { c.store.removeDocument(saved.book.ref.id) } } })
    } } }
    volumeManager?.let { parent -> WenkuVolumeManager(parent, state.books, { volumeManager = null }) { keys ->
        c.store.update { it.withWenkuVolumes(parent.book.ref.key, keys) }
        volumeManager = null
    } }
    volumeParentPicker?.let { volume -> VolumeParentPicker(volume, state.books, { volumeParentPicker = null }) { parentKey ->
        c.store.update { it.withVolumeParent(volume.book.ref.key, parentKey) }
        volumeParentPicker = null
    } }
}

@Composable private fun ShelfControlReveal(visible: Boolean, content: @Composable () -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(160)) + expandVertically(tween(220), expandFrom = Alignment.Top),
        exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(100)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Top)
    ) { content() }
}

@Composable fun FavoriteSheet(c: AppController, book: BookCard, dismiss: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    var cloud by remember { mutableStateOf(false) }
    AppSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text("收藏到书架", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            ChoiceRow("保存位置", listOf("此设备", "原站云端"), if(cloud) 1 else 0) { cloud = it == 1 }
            if(!cloud) state.folders.forEach { folder -> MenuRow(folder, "本地收藏", Icons.Outlined.Folder, { c.store.saveBook(book, folder); c.message("已加入 $folder"); dismiss() }) }
            else if(profile == null) EmptyState("登录后使用云端收藏", "本地收藏仍然可用。", action = "登录", onAction = { dismiss(); c.go("login") })
            else AsyncContent(profile?.username, load = { c.api.get<CloudFolders>("user/favored") }, modifier = Modifier.heightIn(max = 360.dp)) { data, _ ->
                val folders = if(book.ref.isWenku) data.favoredWenku else data.favoredWeb
                Column { if(folders.isEmpty()) Text("请先在书架的云端收藏中创建收藏夹。", Modifier.padding(20.dp)); folders.forEach { folder -> MenuRow(folder.title, "与原站同步", Icons.Outlined.CloudQueue, {
                    c.action {
                        val path = if(book.ref.isWenku) "user/favored-wenku/${folder.id}/${book.ref.id}" else "user/favored-web/${folder.id}/${book.ref.key}"
                        val queued = c.cloudMutation("PUT", path)
                        c.store.saveBook(book); dismiss()
                        if(!queued) c.message("已加入云端收藏")
                    }
                }) } }
            }
        }
    }
}

@Composable fun HistoryScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    val history = remember(state.positions) { state.positions.entries.sortedByDescending { it.value.updatedAt } }
    val booksByKey = remember(state.books) { state.books.associateBy { it.book.ref.key } }
    var tab by remember { mutableIntStateOf(0) }; var page by remember { mutableIntStateOf(0) }; var version by remember { mutableIntStateOf(0) }; var clear by remember { mutableStateOf(false) }
    fun pauseHistory(value: Boolean) { c.store.update { it.copy(historyPaused = value) }; if(profile != null) c.action { c.api.request(if(value) "PUT" else "DELETE", "user/read-history/paused") } }
    Screen("阅读历史", c::back, actions = { IconButton(onClick = { clear = true }) { Icon(Icons.Outlined.DeleteSweep, "清空历史") } }) { padding -> Column(Modifier.padding(padding)) {
        ChoiceRow("记录位置", listOf("此设备", "原站云端"), tab) { tab = it }
        MenuRow("暂停阅读历史", "暂停后不记录新的阅读位置", Icons.Outlined.HistoryToggleOff, { pauseHistory(!state.historyPaused) }, trailing = { Switch(state.historyPaused, ::pauseHistory) })
        if(tab == 0) AppLazyColumn {
            if(history.isEmpty()) item { EmptyState("还没有阅读记录", "打开一本小说，阅读进度就会出现在这里。") }
            items(history, key = { it.key }, contentType = { "book" }) { (key, position) -> val book = booksByKey[key]?.book ?: BookCard(BookRef.fromKey(key), position.title); BookRow(book.copy(subtitle = position.title), { c.read(book.ref, position.chapterId) }) }
        } else if(profile == null) EmptyState("登录以查看云端历史", "原站同步到章节，本设备还会保存段落位置。", action = "登录", onAction = { c.go("login") })
        else AsyncContent(listOf(page, profile?.username), refreshKey = version, load = { c.api.get<Page<WebOutline>>("user/read-history", mapOf("page" to "$page", "pageSize" to "20")) }) { result, _ ->
            val cards = remember(result.items) { result.items.map(WebOutline::card) }
            AppLazyColumn { if(cards.isEmpty()) item { EmptyState("暂无云端阅读历史", "登录后阅读的小说会出现在这里。") }; items(cards, key = { it.ref.key }, contentType = { "book" }) { book -> BookRow(book, { c.book(book.ref) }) }; item { PageControls(page, result.pageNumber) { page = it } } }
        }
    } }
    if(clear) ConfirmDialog("清空阅读历史？", if(tab == 0) "此设备保存的阅读位置将被清除。" else "原站账号下的全部阅读历史将被清除。", { clear = false }) { if(tab == 0) c.store.update { it.copy(positions = emptyMap()) } else c.action { c.api.request("DELETE", "user/read-history"); version++ } }
}
