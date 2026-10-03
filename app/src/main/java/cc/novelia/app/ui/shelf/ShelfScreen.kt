@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.data.updates.withAcknowledgedBookUpdates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.moveShelfBooks
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.ui.components.CharacterCountFilterSaver
import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.library.readingStatuses
import cc.novelia.app.data.library.shelfGroups
import cc.novelia.app.data.library.withReadingStatus
import cc.novelia.app.data.library.withVolumeParent
import cc.novelia.app.data.library.withWenkuVolumeOrder
import cc.novelia.app.data.library.withWenkuVolumes
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.updates.UpdateWorker
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.bookRowStatus
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.CreateBookDocument
import cc.novelia.app.files.prepareLocalBookExport
import cc.novelia.app.files.exportLocalBook
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.TextPrompt
import cc.novelia.app.ui.components.rememberDebouncedQuery
import cc.novelia.app.ui.components.rememberCloudFilterCollapse
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable fun ShelfScreen(c: AppController, onOpenBook: (BookRef) -> Unit = c::book, selectedBookKey: String? = null) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val profile by c.session.profile.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }; var query by rememberSaveable { mutableStateOf("") }; var folder by rememberSaveable { mutableStateOf("全部") }; var sort by rememberSaveable { mutableIntStateOf(0) }
    var bookType by rememberSaveable { mutableStateOf(ShelfBookType.All) }
    var fileType by rememberSaveable { mutableStateOf(ShelfBookType.All) }
    var filtersExpanded by remember(tab) { mutableStateOf(false) }
    var readingStatus by rememberSaveable { mutableStateOf("全部") }
    var characterFilter by rememberSaveable(stateSaver = CharacterCountFilterSaver) { mutableStateOf(CharacterCountFilter()) }
    var bulkStatus by remember { mutableStateOf(false) }
    var createFolder by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf<SavedBook?>(null) }; var managing by remember { mutableStateOf(false) }; var selection by remember { mutableStateOf(setOf<String>()) }; var bulkMove by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf(false) }; var deleteFolder by remember { mutableStateOf(false) }; var localExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var localExportOriginal by rememberSaveable { mutableStateOf(true) }
    var queueingDownloads by remember { mutableStateOf(false) }
    var volumeManager by remember { mutableStateOf<SavedBook?>(null) }
    var volumeParentPicker by remember { mutableStateOf<SavedBook?>(null) }
    var deletingDocument by remember { mutableStateOf<SavedBook?>(null) }
    var renamingDocument by remember { mutableStateOf<SavedBook?>(null) }
    var importedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val sourceExporter = rememberLauncherForActivityResult(CreateBookDocument()) { uri ->
        val pendingId = localExportId
        val original = localExportOriginal
        localExportId = null
        if(uri != null) c.action(if(original) "原文件已导出" else "正文已导出为 TXT") {
            val id = requireNotNull(pendingId) { "待导出的小说已不存在，请重新选择" }
            withContext(Dispatchers.IO) {
                val workContext = coroutineContext
                exportLocalBook(c.store, id, original, { c.app.contentResolver.openOutputStream(uri, "wt") }) { workContext.ensureActive() }
            }
        }
    }
    val importModel = rememberDocumentImporter(c)
    val importState by importModel.state.collectAsStateWithLifecycle()
    val importing = importState.running
    LaunchedEffect(importState.bookKeys) { importedKeys = importState.bookKeys }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importModel.start(uris)
    }
    val recent = remember(state.books, state.positions) {
        state.books.asSequence().filter { state.positions.containsKey(it.book.ref.key) }.maxByOrNull { state.positions.getValue(it.book.ref.key).updatedAt }
    }
    val settledQuery = rememberDebouncedQuery(query)
    var collapsedSearchGroups by remember(settledQuery, readingStatus) { mutableStateOf(setOf<String>()) }
    val filteringVolumes = settledQuery.isNotBlank() || readingStatus != "全部"
    val reducedMotion = appReducedMotion()
    val tabState = rememberSaveableStateHolder()
    val baseGroups = remember(state.books, state.positions, tab, folder, settledQuery, sort, bookType, fileType, readingStatus) {
        state.shelfGroups(localOnly = tab == 1, folder = folder, query = settledQuery, sort = sort,
            type = if(tab == 0) bookType else fileType, status = readingStatus)
    }
    val filterCharacters = tab == 0 && bookType == ShelfBookType.Web && characterFilter.active
    val countCandidates = if(tab == 0 && bookType == ShelfBookType.Web) baseGroups.map { it.saved.book } else emptyList()
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val characterCounts = rememberShelfCharacterCounts(c, countCandidates, filterCharacters, profile?.username, cacheGeneration)
    val groups = if(filterCharacters) baseGroups.filter { characterFilter.matches(characterCounts.count(it.saved.book)) } else baseGroups
    val books = remember(groups) { groups.flatMap { (if(it.matchesFilters) listOf(it.saved) else emptyList()) + it.volumes } }
    val selectableKeys = remember(books) { books.map { it.book.ref.key }.toSet() }
    LaunchedEffect(selectableKeys) { selection = selection.intersect(selectableKeys) }
    val rows = remember(groups, collapsedSearchGroups, filteringVolumes) {
        buildList {
            groups.forEach { group ->
                val expanded = if(filteringVolumes) group.saved.book.ref.key !in collapsedSearchGroups else group.saved.volumesExpanded
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
            DocumentImportPanel(importModel, c::book)
            MotionContent(tab, Modifier.weight(1f).fillMaxWidth(), animateInitial = false) {
                tabState.SaveableStateProvider(tab) {
                    if(tab == 2) CloudShelf(c, onOpenBook, selectedBookKey) else {
                        val listState = rememberLazyListState()
                        val canReorder = tab == 0 && !managing && query.isBlank() && settledQuery.isBlank() && readingStatus == "全部"
                        val reorder = rememberVolumeReorderState(listState, rows, canReorder) { parent, keys ->
                            c.store.update { current ->
                                // 下载可能在拖动期间完成，新挂载的分卷保留在顺序末尾。
                                val siblings = current.books.filter { it.book.ref.isLocal && it.parentWenkuKey == parent }.map { it.book.ref.key }
                                if(current.books.none { it.book.ref.key == parent && it.book.ref.isWenku }) current
                                else current.withWenkuVolumeOrder(parent, keys.filter { it in siblings } + siblings.filter { it !in keys })
                            }
                        }
                        val collapse = rememberCloudFilterCollapse(true, filtersExpanded) { filtersExpanded = false }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val filterHeight = maxHeight * .55f
                            Column(Modifier.fillMaxSize()) {
                                LocalShelfFilters(tab == 1, if(tab == 0) bookType else fileType, { if(tab == 0) bookType = it else fileType = it },
                                    state.folders, folder, { folder = it }, sort, { sort = it }, query, { query = it }, readingStatus, { readingStatus = it },
                                    filtersExpanded, { filtersExpanded = it }, filterHeight, { createFolder = true }, { renameFolder = true }, { deleteFolder = true },
                                    characters = characterFilter, onCharacters = { characterFilter = it })
                                if(filterCharacters) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    val unknown = countCandidates.count { characterCounts.count(it) == null }
                                    Text(if(characterCounts.loading) "正在补全字数…" else "$unknown 本字数未知", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    if(unknown > 0) TextButton(onClick = { characterCounts.loadMore(countCandidates) }, enabled = !characterCounts.loading) { Text("补全字数") }
                                }
                                AppLazyColumn(modifier = Modifier.weight(1f).nestedScroll(collapse), listModifier = Modifier.testTag("shelf-books"), state = listState,
                                    onPageTurn = { if(it > 0) filtersExpanded = false }) {
                                    val importedBooks = importedKeys.mapNotNull { key -> state.books.firstOrNull { it.book.ref.key == key } }
                                    if(importedBooks.isNotEmpty()) item(key = "imported-books", contentType = "hero") {
                                        Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text("已准备好阅读", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                                    IconButton(onClick = { importedKeys = emptyList() }) { Icon(Icons.Outlined.Close, "关闭导入结果") }
                                                }
                                                importedBooks.take(3).forEach { saved ->
                                                    Text(saved.book.title, maxLines = 2)
                                                    Button(onClick = { c.book(saved.book.ref) }, modifier = Modifier.fillMaxWidth()) { Text("开始阅读") }
                                                }
                                                if(importedBooks.size > 3) Text("另有 ${importedBooks.size - 3} 本小说已加入书架。", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    if(recent != null && tab == 0 && query.isBlank() && folder == "全部" && bookType == ShelfBookType.All && readingStatus == "全部") item(key = "continue-reading", contentType = "hero") {
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
                                    item(key = "sort", contentType = "controls") { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                        val volumeCount = groups.sumOf { it.volumes.size }
                                        Text("${groups.size} 本" + if(volumeCount > 0) " · $volumeCount 分卷" else "", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                                        IconButton(onClick = { managing = !managing; selection = emptySet() }) { MotionContent(managing, animateInitial = false) { Icon(if(managing) Icons.Outlined.Check else Icons.Outlined.Checklist, if(managing) "完成整理" else "批量整理") } }
                                    } }
                                    item(key = "bulk-actions", contentType = "controls") { ShelfControlReveal(managing) { FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { selection = books.map { it.book.ref.key }.toSet() }, enabled = managing) { Text("全选") }
                                        FilledTonalButton(onClick = { bulkMove = true }, enabled = managing && selection.isNotEmpty()) { Text("移动 ${selection.size} 本") }
                                        FilledTonalButton(onClick = { bulkStatus = true }, enabled = managing && selection.isNotEmpty()) { Text("修改阅读状态") }
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
                                                    c.message("已开始下载 ${chosen.size} 本网络小说", actionLabel = "查看下载") { c.go("downloads", replaceTop = true) }
                                                    managing = false
                                                    selection = emptySet()
                                                } finally { queueingDownloads = false }
                                            }
                                        }, enabled = managing && !queueingDownloads && downloadableSelection.isNotEmpty()) { Text(if(queueingDownloads) "正在加入…" else "下载") }
                                    } } }
                                    if(books.isEmpty()) item {
                                        if(query.isNotBlank() || folder != "全部" || readingStatus != "全部" || (if(tab == 0) bookType else fileType) != ShelfBookType.All)
                                            EmptyState("没有符合条件的小说", if(filterCharacters) "可以放宽字数范围、包含未知作品或继续补全字数。" else "试试其他书名、作者、类型或阅读状态。", action = "清除筛选", onAction = { query = ""; folder = "全部"; readingStatus = "全部"; bookType = ShelfBookType.All; fileType = ShelfBookType.All; characterFilter = CharacterCountFilter() })
                                        else EmptyState(if(tab == 1) "把故事装进口袋" else "书架等你来填满", if(tab == 1) "支持 EPUB、TXT 和 SRT，导入后即可离线阅读。" else "去发现喜欢的小说，或导入你已有的文件。", action = if(tab == 1) "导入文件" else "去发现", onAction = { if(tab == 1) importer.launch(arrayOf("*/*")) else c.go("discover") }, sticker = MidoriSticker.Welcome)
                                    }
                                    items(reorder.rows, key = { it.saved.book.ref.key }, contentType = { if(it.parent == null) "book" else "volume" }) { row ->
                                        val saved = row.saved
                                        LaunchedEffect(saved.book.ref, profile?.username, state.syncStatus[profile?.username]?.lastSuccessAt) {
                                            if(profile == null || saved.book.ref.isLocal || saved.book.ref.isWenku) return@LaunchedEffect
                                            try { c.refreshCloudReading(saved.book) }
                                            catch(e: kotlinx.coroutines.CancellationException) { throw e }
                                            catch(_: Exception) { /* 离线时保留已知的本地和云端位置。 */ }
                                        }
                                        val dragging = reorder.draggedKey == saved.book.ref.key
                                        val selectionColor = animateColorAsState(
                                            when {
                                                managing && saved.book.ref.key in selection -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)
                                                !managing && saved.book.ref.key == selectedBookKey -> MaterialTheme.colorScheme.secondaryContainer
                                                else -> Color.Transparent
                                            },
                                            animationSpec = tween(if(reducedMotion) 0 else AppMotion.Release), label = "shelf-selection"
                                        )
                                        val itemMotion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = if(dragging) null else tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))
                                        val selectable = saved.book.ref.key in selectableKeys
                                        val onOpen = { if(managing) { if(selectable) selection = if(saved.book.ref.key in selection) selection - saved.book.ref.key else selection + saved.book.ref.key } else { filtersExpanded = false; onOpenBook(saved.book.ref) } }
                                        val trailing: @Composable () -> Unit = {
                                            if(managing) Checkbox(saved.book.ref.key in selection, { checked -> selection = if(checked) selection + saved.book.ref.key else selection - saved.book.ref.key }, enabled = selectable) else IconButton(onClick = { selected = saved }) { Icon(Icons.Outlined.MoreVert, "管理 ${saved.book.title}") }
                                        }
                                        if(row.parent != null) MountedVolumeRow(saved, state.positions[saved.book.ref.key],
                                            itemMotion.zIndex(if(dragging) 1f else 0f).graphicsLayer {
                                                translationY = if(dragging) reorder.offset else 0f
                                                shadowElevation = if(dragging && !reducedMotion) 4.dp.toPx() else 0f
                                            }.drawBehind { drawRect(selectionColor.value) }.semantics { if(!managing && saved.book.ref.key == selectedBookKey) stateDescription = "已选中" }, onOpen, trailing,
                                            dragHandle = if(canReorder && reorder.siblings(saved.book.ref.key).size > 1) {
                                                { VolumeDragHandle(reorder, saved.book.ref.key, saved.book.title) }
                                            } else null)
                                        else Column(itemMotion.testTag("shelf-book-${saved.book.ref.key}").drawBehind { drawRect(selectionColor.value) }
                                            .semantics { if(!managing && saved.book.ref.key == selectedBookKey) stateDescription = "已选中" }) {
                                            BookRow(if(filterCharacters) saved.book.copy(totalCharacters = characterCounts.count(saved.book)) else saved.book, onOpen,
                                                status = bookRowStatus(saved.book, saved, state.positions[saved.book.ref.key], state.bookUpdates[saved.book.ref.key], profile?.username),
                                                showCharacterCount = filterCharacters, trailing = trailing)
                                            if(saved.book.ref.isWenku && !managing) {
                                                val rotation by animateFloatAsState(if(row.expanded) 180f else 0f, tween(if(reducedMotion) 0 else AppMotion.Standard), label = "wenku-volume-disclosure")
                                                TextButton(onClick = {
                                                    if(row.volumeCount == 0) volumeManager = saved
                                                    else if(filteringVolumes) collapsedSearchGroups = if(row.expanded) collapsedSearchGroups + saved.book.ref.key else collapsedSearchGroups - saved.book.ref.key
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
        }
    }
    if(createFolder) TextPrompt("新建收藏夹", "名称", onDismiss = { createFolder = false }) { name -> c.store.update { it.copy(folders = (it.folders + name).distinct()) } }
    if(renameFolder) TextPrompt("重命名收藏夹", "名称", folder, { renameFolder = false }) { name -> val previous = folder; c.store.update { it.copy(folders = (it.folders.map { f -> if(f == previous) name else f }).distinct(), books = it.books.map { b -> if(b.folder == previous) b.copy(folder = name) else b }) }; folder = name }
    if(deleteFolder) ConfirmDialog("删除收藏夹？", "其中的书籍会移入默认收藏，文件不会删除。", { deleteFolder = false }, confirmLabel = "删除收藏夹") { val previous = folder; c.store.update { it.copy(folders = it.folders - previous, books = it.books.map { b -> if(b.folder == previous) b.copy(folder = "默认收藏") else b }) }; folder = "全部" }
    if(bulkMove) AppAlertDialog(onDismissRequest = { bulkMove = false }, title = { Text("移入收藏夹") }, text = { Column {
        if(state.books.any { it.book.ref.key in selection && it.parentWenkuKey != null && it.parentWenkuKey !in selection }) Text("单独移动分卷会取消其挂载；同时移动所属文库可保留挂载。", style = MaterialTheme.typography.bodySmall)
        state.folders.forEach { target -> TextButton(onClick = { c.store.update { it.moveShelfBooks(selection, target) }; bulkMove = false; managing = false; selection = emptySet() }) { Text(target) } }
    } }, confirmButton = {})
    if(bulkStatus) AppAlertDialog(onDismissRequest = { bulkStatus = false }, title = { Text("修改 ${selection.size} 本的阅读状态") }, text = { Column {
        readingStatuses.forEach { status -> TextButton(onClick = {
            c.store.update { it.withReadingStatus(selection, status) }
            bulkStatus = false; managing = false; selection = emptySet()
        }) { Text(status) } }
    } }, confirmButton = {})
    renamingDocument?.let { saved -> TextPrompt("重命名本地小说", "名称", saved.book.title, { renamingDocument = null }) { name ->
        c.action("小说已重命名") { withContext(Dispatchers.IO) { c.store.renameDocument(saved.book.ref.id, name) } }
    } }
    selected?.let { saved -> AppSheet(onDismissRequest = { selected = null }) { AppScrollColumn(modifier = Modifier.navigationBarsPadding(), contentModifier = Modifier.padding(bottom = 28.dp)) {
        Text(saved.book.title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2)
        if(saved.hasUpdates) MenuRow("标记更新已读", "清除本书的更新提示", Icons.Outlined.DoneAll, { c.store.update { it.withAcknowledgedBookUpdates(saved.book.ref) }; selected = null })
        MenuRow(if(saved.pinned) "取消置顶" else "置顶", "在书架顶部显示", Icons.Outlined.PushPin, { c.store.update { it.copy(books = it.books.map { b -> if(b.book.ref == saved.book.ref) b.copy(pinned = !b.pinned) else b }) }; selected = null })
        ChoiceRow("阅读状态", readingStatuses, readingStatuses.indexOf(saved.status)) { status -> c.store.update { it.withReadingStatus(setOf(saved.book.ref.key), readingStatuses[status]) }; selected = null }
        val parent = state.books.firstOrNull { it.book.ref.isWenku && it.book.ref.key == saved.parentWenkuKey }
        if(parent == null) ChoiceRow("收藏夹", state.folders, state.folders.indexOf(saved.folder)) { index -> c.store.saveBook(saved.book, state.folders[index]); selected = null }
        else Text("所属收藏夹：${parent.folder}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if(saved.book.ref.isWenku) MenuRow("管理挂载分卷", "选择已导入的分卷，在本书下方展开阅读", Icons.Outlined.LibraryAdd, { selected = null; volumeManager = saved })
        if(saved.book.ref.isLocal) MenuRow(if(parent == null) "挂载到文库小说" else "更换或取消挂载", parent?.let { "当前挂载：${it.book.title}" } ?: "归入指定的文库收藏", Icons.Outlined.DriveFileMove, { selected = null; volumeParentPicker = saved })
        if(saved.book.ref.isLocal) MenuRow("重命名", "修改本地小说或分卷名称", Icons.Outlined.Edit, { selected = null; renamingDocument = saved })
        if(saved.book.ref.isLocal) MenuRow("本地术语表", "维护此文件的专有名词", Icons.Outlined.Translate, { selected = null; c.go("glossary/${saved.book.ref.key}") })
        if(saved.book.ref.isLocal) MenuRow("导出原文件", "保留原格式；原件缺失时导出正文 TXT", Icons.Outlined.IosShare, { c.action {
            val export = withContext(Dispatchers.IO) { prepareLocalBookExport(c.store, saved.book.ref.id) }
            localExportId = saved.book.ref.id
            localExportOriginal = export.original
            selected = null
            sourceExporter.launch(export.fileName)
        } })
        MenuRow("移出书架", "保留文件和阅读记录，移除后可撤销", Icons.Outlined.RemoveCircleOutline, {
            val previous = c.store.state.value.books
            c.store.removeBook(saved.book.ref); selected = null
            c.action {
                if(c.snackbar.showSnackbar("已将「${saved.book.title}」移出书架", actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    c.store.update { current ->
                        restoreRemovedShelfBook(current, previous, saved.book.ref)
                    }
                }
            }
        })
        if(saved.book.ref.isLocal) MenuRow("删除本地小说", "删除此文件的导入副本", Icons.Outlined.DeleteOutline, { selected = null; deletingDocument = saved })
    } } }
    volumeManager?.let { parent -> WenkuVolumeManager(parent, state.books, { volumeManager = null }) { keys ->
        c.store.update { it.withWenkuVolumes(parent.book.ref.key, keys) }
        volumeManager = null
    } }
    volumeParentPicker?.let { volume -> VolumeParentPicker(volume, state.books, { volumeParentPicker = null }) { parentKey ->
        c.store.update { it.withVolumeParent(volume.book.ref.key, parentKey) }
        volumeParentPicker = null
    } }
    deletingDocument?.let { saved -> ConfirmDialog("删除「${saved.book.title}」？", "将删除此设备中的导入副本，并移出书架。导入前的原文件和下载列表中的文件不受影响。此操作无法撤销。", { deletingDocument = null }, confirmLabel = "删除本地小说") {
        c.action("本地小说已删除") { withContext(Dispatchers.IO) { c.store.removeDocument(saved.book.ref.id) } }
    } }
}

@Composable private fun ShelfControlReveal(visible: Boolean, content: @Composable () -> Unit) {
    val reducedMotion = appReducedMotion()
    AnimatedVisibility(
        visible = visible,
        enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + expandVertically(tween(AppMotion.Standard), expandFrom = Alignment.Top),
        exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + shrinkVertically(tween(AppMotion.Release), shrinkTowards = Alignment.Top)
    ) { content() }
}
