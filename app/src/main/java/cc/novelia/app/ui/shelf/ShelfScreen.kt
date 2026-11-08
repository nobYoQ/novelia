@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import android.net.Uri
import androidx.activity.compose.BackHandler
import cc.novelia.app.ui.components.base.AppCheckbox
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.data.updates.withAcknowledgedBookUpdates
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.moveShelfBooks
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.ui.components.base.CharacterCountFilterSaver
import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.library.readingStatuses
import cc.novelia.app.data.library.shelfGroups
import cc.novelia.app.data.library.withReadingStatus
import cc.novelia.app.data.library.withAutomaticReadingStatus
import cc.novelia.app.data.library.withVolumeParent
import cc.novelia.app.data.library.withWenkuVolumeOrder
import cc.novelia.app.data.library.withWenkuVolumes
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.createShelfFolder
import cc.novelia.app.data.model.renameShelfFolder
import cc.novelia.app.data.model.deleteShelfFolder
import cc.novelia.app.data.model.restoredShelfLocation
import cc.novelia.app.data.updates.UpdateWorker
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.components.book.BookRow
import cc.novelia.app.ui.components.book.bookRowStatus
import cc.novelia.app.ui.components.base.ChoiceRow
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.components.documents.CreateBookDocument
import cc.novelia.app.ui.components.documents.DocumentPickerFallback
import cc.novelia.app.ui.components.documents.DocumentPickerTypes
import cc.novelia.app.ui.components.documents.rememberOpenMultipleDocumentsPicker
import cc.novelia.app.files.exporting.prepareLocalBookExport
import cc.novelia.app.files.exporting.exportLocalBook
import cc.novelia.app.files.downloads.deleteBookFiles
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.MenuRow
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.components.base.TextPrompt
import cc.novelia.app.ui.components.base.rememberDebouncedQuery
import cc.novelia.app.ui.feedback.Sticker
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.LocalRootSelectionMode
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable fun ShelfScreen(c: AppController, onOpenBook: (BookRef) -> Unit = c::book, selectedBookKey: String? = null,
    sharedDocuments: List<Uri> = emptyList(), onSharedDocumentsConsumed: (List<Uri>) -> Unit = {}) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val profile by c.session.profile.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(state.restoredShelfLocation().tab) }
    var folderId by rememberSaveable { mutableStateOf(state.restoredShelfLocation().localFolderId) }
    val folder = state.folders.firstOrNull { state.folderIds[it] == folderId } ?: "全部"
    var query by rememberSaveable { mutableStateOf("") }; var sort by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(tab, folderId, state.folderIds, state.rememberLastShelfFolder) {
        val validFolderId = folderId?.takeIf { it in state.folderIds.values }
        folderId = validFolderId
        c.store.update { if(it.rememberLastShelfFolder) it.copy(lastShelfLocation = it.lastShelfLocation.copy(
            tab = tab, localFolderId = validFolderId)) else it }
    }
    var bookType by rememberSaveable { mutableStateOf(ShelfBookType.All) }
    var fileType by rememberSaveable { mutableStateOf(ShelfBookType.All) }
    var readingStatus by rememberSaveable { mutableStateOf("全部") }
    var characterFilter by rememberSaveable(stateSaver = CharacterCountFilterSaver) { mutableStateOf(CharacterCountFilter()) }
    var bulkStatus by remember { mutableStateOf(false) }
    var createFolder by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf<SavedBook?>(null) }; var managing by remember { mutableStateOf(false) }; var selection by remember { mutableStateOf(setOf<String>()) }; var bulkMove by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf(false) }; var deleteFolder by remember { mutableStateOf(false) }; var localExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var localExportOriginal by rememberSaveable { mutableStateOf(true) }
    var queueingDownloads by remember { mutableStateOf(false) }
    var volumeManager by remember { mutableStateOf<SavedBook?>(null) }
    var volumeSheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var volumeMatches by remember { mutableStateOf<Set<String>?>(null) }
    var volumeParentPicker by remember { mutableStateOf<SavedBook?>(null) }
    var deletingDocument by remember { mutableStateOf<SavedBook?>(null) }
    var batchRemoval by remember { mutableStateOf<Set<BookRef>?>(null) }
    var batchDeletion by remember { mutableStateOf<Set<BookRef>?>(null) }
    var permanentDeletion by remember { mutableStateOf<Set<BookRef>?>(null) }
    var batchWorking by remember { mutableStateOf(false) }
    var cloudManaging by remember { mutableStateOf(false) }
    var cloudBusy by remember { mutableStateOf(false) }
    val updateCloudSelection: (Boolean, Boolean) -> Unit = remember { { selecting, busy -> cloudManaging = selecting; cloudBusy = busy } }
    val rootSelectionMode = LocalRootSelectionMode.current
    SideEffect { rootSelectionMode((managing && tab < 2) || (cloudManaging && tab == 2)) }
    DisposableEffect(rootSelectionMode) { onDispose { rootSelectionMode(false) } }
    BackHandler(enabled = managing) { if(!batchWorking) { managing = false; selection = emptySet() } }
    var renamingDocument by remember { mutableStateOf<SavedBook?>(null) }
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
    LaunchedEffect(sharedDocuments, importing) {
        if(sharedDocuments.isNotEmpty() && !importing) {
            tab = 1
            importModel.start(sharedDocuments)
            onSharedDocumentsConsumed(sharedDocuments)
        }
    }
    val importer = rememberOpenMultipleDocumentsPicker(DocumentPickerTypes.Books) { uris ->
        importModel.start(uris)
    }
    val importLocalFiles = {
        if(!importing) importer.launch()
    }
    val comicFolder = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) importModel.start(listOf(tree))
    }
    var importMenu by remember { mutableStateOf(false) }
    val recent = remember(state.books, state.positions) {
        state.books.asSequence().filter { state.positions.containsKey(it.book.ref.key) }.maxByOrNull { state.positions.getValue(it.book.ref.key).updatedAt }
    }
    val settledQuery = rememberDebouncedQuery(query)
    var collapsedSearchGroups by remember(settledQuery, readingStatus) { mutableStateOf(setOf<String>()) }
    val filteringVolumes = settledQuery.isNotBlank() || readingStatus != "全部"
    val reducedMotion = appReducedMotion()
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val shelfGroups = remember(state.books, state.positions, folder, settledQuery, sort, bookType, readingStatus) {
        state.shelfGroups(localOnly = false, folder = folder, query = settledQuery, sort = sort, type = bookType, status = readingStatus)
    }
    val filterShelfCharacters = bookType == ShelfBookType.Web && characterFilter.active
    val countCandidates = if(bookType == ShelfBookType.Web) shelfGroups.map { it.saved.book } else emptyList()
    // 字数结果留在分页器外，离开书架分区后返回时无需重新补全。
    val characterCounts = rememberShelfCharacterCounts(c, countCandidates, tab == 0 && filterShelfCharacters, profile?.username, cacheGeneration)
    LaunchedEffect(tab) { managing = false; selection = emptySet(); volumeSheetKey = null }
    val allVolumeGroups = remember(state.books) {
        state.shelfGroups(localOnly = false, folder = "全部", query = "", sort = 2).associateBy { it.saved.book.ref.key }
    }
    val commitVolumeOrder: (String, List<String>) -> Unit = { parent, keys ->
        c.store.update { current ->
            val siblings = current.books.filter { it.book.ref.isLocal && it.parentWenkuKey == parent }.map { it.book.ref.key }
            if(current.books.none { it.book.ref.key == parent && it.book.ref.isWenku }) current
            else current.withWenkuVolumeOrder(parent, keys.filter { it in siblings } + siblings.filter { it !in keys })
        }
    }
    val selectionActions: @Composable (Boolean, List<SavedBook>) -> Unit = { localFiles, chosen ->
        val downloadable = chosen.filter { !it.book.ref.isLocal && !it.book.ref.isWenku }
        ShelfSelectionActions(chosen.size, localFiles, batchWorking, queueingDownloads,
            canDownload = downloadable.isNotEmpty(), onMove = { bulkMove = true }, onStatus = { bulkStatus = true },
            onDownload = {
                queueingDownloads = true
                c.action {
                    try {
                        val invalidFilenameChars = Regex("[\\/\\\\:*?\"<>|]")
                        val reader = state.reader
                        downloadable.forEach { saved ->
                            val id = java.util.UUID.randomUUID().toString()
                            val filename = saved.book.title.replace(invalidFilenameChars, "_").take(120) + ".epub"
                            val url = c.api.downloadUrl(saved.book.ref, null, reader.mode, reader.engines, reader.parallel, "epub", filename)
                            cc.novelia.app.files.DownloadWorker.enqueue(c.app, DownloadEntry(id, saved.book.title, "$id-$filename", url, sourceBook = saved.book.ref, sourceCard = saved.book))
                        }
                        c.message("已开始下载 ${downloadable.size} 本网络小说", actionLabel = "查看下载") { c.go("downloads", replaceTop = true) }
                        managing = false; selection = emptySet()
                    } finally { queueingDownloads = false }
                }
            }, onRemove = {
                val refs = chosen.map { it.book.ref }.toSet()
                if(localFiles) batchDeletion = refs.filter { it.isLocal }.toSet() else batchRemoval = refs
            }, onDelete = { permanentDeletion = chosen.map { it.book.ref }.toSet() },
            onSingleBook = chosen.singleOrNull()?.let { single -> { selected = single } })
    }
    Screen("书架", actions = {
        AppIconButton(onClick = { c.go("updates") }) { Icon(Icons.Outlined.NewReleases, "查看书架更新") }
        AppIconButton(onClick = { UpdateWorker.checkNow(c.app); c.message("已开始检查书架更新") }) { Icon(Icons.Outlined.Sync, "检查更新") }
        AppIconButton(onClick = { c.go("history") }) { Icon(Icons.Outlined.History, "阅读历史") }
        Box {
            AppIconButton(onClick = { importMenu = true }, enabled = !importing) { Icon(Icons.Outlined.FileOpen, "导入本地文件") }
            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                DropdownMenuItem(text = { Text("选择书籍文件") }, onClick = { importMenu = false; importLocalFiles() })
                DropdownMenuItem(text = { Text("选择漫画图片文件夹") }, onClick = { importMenu = false; comicFolder.launch(null) })
            }
        }
    }) { padding ->
        ShelfPager(tab, { tab = it }, enabled = !batchWorking && !cloudBusy, modifier = Modifier.padding(padding),
            controls = {
                DocumentPickerFallback(importer, enabled = !importing)
                DocumentImportPanel(importModel, c::book)
            }) { page ->
            val active = page == tab
            if(page == 2) CloudShelf(c, onOpenBook, selectedBookKey, active = active, onSelectionStateChanged = updateCloudSelection) else BoxWithConstraints(Modifier.fillMaxSize()) {
                // 邻页预组合时按自己的分区生成列表，不能复用当前选中页的数据或修剪其批量选择。
                val baseGroups = if(page == 0) shelfGroups else remember(state.books, state.positions, folder, settledQuery, sort, fileType, readingStatus) {
                    state.shelfGroups(localOnly = true, folder = folder, query = settledQuery, sort = sort,
                        type = fileType, status = readingStatus)
                }
                val filterCharacters = page == 0 && filterShelfCharacters
                val groups = if(filterCharacters) baseGroups.filter { characterFilter.matches(characterCounts.count(it.saved.book)) } else baseGroups
                val books = remember(groups) { groups.flatMap { (if(it.matchesFilters) listOf(it.saved) else emptyList()) + it.volumes } }
                val grid = if(page == 0) state.shelfGrid else state.localFilesGrid
                val columns = shelfGridColumns(maxWidth)
                val gridRows = remember(groups, columns) { groups.chunked(columns) }
                val selectableKeys = remember(books, groups, grid) {
                    (if(grid) groups.filter { it.matchesFilters }.map { it.saved } else books).map { it.book.ref.key }.toSet()
                }
                LaunchedEffect(active, selectableKeys, volumeSheetKey) { if(active && volumeSheetKey == null) selection = selection.intersect(selectableKeys) }
                val rows = remember(groups, collapsedSearchGroups, filteringVolumes) {
                    buildList {
                        groups.forEach { group ->
                            val expanded = if(filteringVolumes) group.saved.book.ref.key !in collapsedSearchGroups else group.saved.volumesExpanded
                            add(ShelfRowItem(group.saved, volumeCount = group.volumes.size, expanded = expanded))
                            if(expanded) group.volumes.forEach { add(ShelfRowItem(it, parent = group.saved)) }
                        }
                    }
                }
                val pageManaging = active && managing && volumeSheetKey == null
                val layout = rememberShelfLayoutState(grid, rows.map { it.saved.book.ref.key }, groups.map { it.saved.book.ref.key }, columns,
                    parents = rows.mapNotNull { row -> row.parent?.let { row.saved.book.ref.key to it.book.ref.key } }.toMap())
                val listState = layout.current(grid)
                val continueBook = recent?.takeIf { page == 0 && active && !pageManaging && volumeSheetKey == null }
                val density = LocalDensity.current
                val collapseThreshold = with(density) { 48.dp.roundToPx() }
                val continueExpanded by remember(listState, collapseThreshold) {
                    derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < collapseThreshold }
                }
                var continueHeight by remember { mutableIntStateOf(0) }
                var listHeight by remember { mutableIntStateOf(0) }
                // 电子纸的翻屏栏也位于列表底部，浮动入口需放在翻屏栏上方。
                val pagingControlsHeight by remember {
                    derivedStateOf { (listHeight - listState.layoutInfo.viewportSize.height).coerceAtLeast(0) }
                }
                var filtersExpanded by remember { mutableStateOf(false) }
                LaunchedEffect(active) { if(!active) filtersExpanded = false }
                val focus = LocalFocusManager.current
                var controlsHeight by remember { mutableIntStateOf(0) }
                val gridHit = rememberShelfGridHitTest(layout.grid, gridRows.map { groupRow -> groupRow.map { it.saved.book.ref.key } }, columns)
                val selectionEnabled = active && !batchWorking && volumeSheetKey == null
                val dragSelection = rememberShelfDragSelection(listState,
                    (if(grid) groups.map { it.saved.book.ref.key } else rows.map { it.saved.book.ref.key }).filter { it in selectableKeys },
                    selection, pageManaging, selectionEnabled,
                    onBegin = { managing = true; filtersExpanded = false; focus.clearFocus() }, onSelection = { selection = it.intersect(selectableKeys) },
                    hitTest = if(grid) gridHit else null)
                val canReorder = active && !grid && page == 0 && !managing && query.isBlank() && settledQuery.isBlank() && readingStatus == "全部"
                val reorder = rememberVolumeReorderState(layout.list, rows, canReorder, commitVolumeOrder)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val filterHeight = maxHeight * .9f
                    Column(Modifier.fillMaxSize()) {
                        LocalShelfFilters(page == 1, if(page == 0) bookType else fileType, { if(page == 0) bookType = it else fileType = it },
                            state.folders, folder, { folderId = state.folderIds[it] }, sort, { sort = it }, query, { query = it }, readingStatus, { readingStatus = it },
                            filtersExpanded, { filtersExpanded = it }, filterHeight, { createFolder = true }, { renameFolder = true }, { deleteFolder = true },
                            characters = characterFilter, onCharacters = { characterFilter = it },
                            countLabel = "${groups.size} 本" + groups.sumOf { it.volumes.size }.takeIf { it > 0 }?.let { " · $it 分卷" }.orEmpty(),
                            visible = !pageManaging, active = active, modifier = Modifier.onSizeChanged { controlsHeight = it.height },
                            grid = grid, onToggleLayout = { c.store.update { if(page == 0) it.copy(shelfGrid = !it.shelfGrid) else it.copy(localFilesGrid = !it.localFilesGrid) } }) {
                            if(filterCharacters) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                val unknown = countCandidates.count { characterCounts.count(it) == null }
                                Text(if(characterCounts.loading) "正在补全字数…" else "$unknown 本字数未知", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if(unknown > 0) AppTextButton(onClick = { characterCounts.loadMore(countCandidates) }, enabled = !characterCounts.loading) { Text("补全字数") }
                            }
                        }
                        if(pageManaging) ShelfSelectionHeader(selection.size, !batchWorking,
                            // 长按尚未松手时保持列表起点，避免进入选择模式后书籍从手指下跳开。
                            if(dragSelection.dragging) with(density) { controlsHeight.toDp() } else 48.dp,
                            onSelectAll = { selection = selectableKeys },
                            onFinish = { managing = false; selection = emptySet() })
                        ShelfLayoutTransition(grid, Modifier.weight(1f)) { showGrid ->
                        AppLazyColumn(modifier = Modifier.fillMaxSize().onSizeChanged { if(showGrid == grid) listHeight = it.height },
                            listModifier = Modifier.testTag(if(showGrid) "shelf-books-grid" else "shelf-books")
                                .shelfDragSelection(dragSelection, selectionEnabled && showGrid == grid), state = layout.current(showGrid),
                            verticalArrangement = Arrangement.spacedBy(if(showGrid) 14.dp else 0.dp),
                            contentPadding = PaddingValues(top = if(showGrid) 8.dp else 0.dp, bottom = if(continueBook != null)
                                with(density) { continueHeight.toDp() } + 32.dp else 0.dp)) {
                            if(books.isEmpty()) item {
                                if(query.isNotBlank() || folder != "全部" || readingStatus != "全部" || (if(page == 0) bookType else fileType) != ShelfBookType.All)
                                    EmptyState("没有符合条件的小说", if(filterCharacters) "可以放宽字数范围、包含未知作品或继续补全字数。" else "试试其他书名、作者、类型或阅读状态。", action = "清除筛选", onAction = { query = ""; folderId = null; readingStatus = "全部"; bookType = ShelfBookType.All; fileType = ShelfBookType.All; characterFilter = CharacterCountFilter() })
                                else EmptyState(if(page == 1) "把故事装进口袋" else "书架等你来填满", if(page == 1) "从文件导入电子书、漫画压缩包，也可以选择漫画图片文件夹。" else "去发现喜欢的小说，或导入你已有的文件。", action = if(page == 1) "导入文件" else "去发现", onAction = { if(page == 1) importMenu = true else c.go("discover") }, sticker = Sticker.Welcome)
                            }
                            if(showGrid) items(gridRows, key = { it.first().saved.book.ref.key }, contentType = { "cover-row" }) { groupRow ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    groupRow.forEach { group -> key(group.saved.book.ref.key) {
                                        val saved = group.saved
                                        val key = saved.book.ref.key
                                        val volumes = allVolumeGroups[key]?.volumes.orEmpty()
                                        val recentVolume = volumes.filter { it.book.ref.key in state.positions }.maxByOrNull { state.positions.getValue(it.book.ref.key).updatedAt }
                                        val status = bookRowStatus(saved.book, saved, state.positions[key], state.bookUpdates[key], profile?.username)
                                        val progress = recentVolume?.let { volume ->
                                            val label = bookRowStatus(volume.book, volume, state.positions[volume.book.ref.key], null).progressLabel
                                            shelfVolumeProgressLabel(saved.book.title, volume.book.title, label)
                                        }
                                        val open = { if(!dragSelection.dragging && !batchWorking) {
                                            if(pageManaging) { if(key in selectableKeys) selection = if(key in selection) selection - key else selection + key }
                                            else { filtersExpanded = false; onOpenBook(saved.book.ref) }
                                        } }
                                        LaunchedEffect(active, saved.book.ref, profile?.username, state.syncStatus[profile?.username]?.lastSuccessAt) {
                                            if(active && profile != null && !saved.book.ref.isLocal && !saved.book.ref.isWenku) {
                                                try { c.refreshCloudReading(saved.book) }
                                                catch(e: kotlinx.coroutines.CancellationException) { throw e }
                                                catch(_: Exception) { /* 离线保留已有进度。 */ }
                                            }
                                        }
                                        ShelfCoverCard(saved.book, status, open, Modifier.weight(1f).testTag("shelf-book-$key").semantics {
                                            this.selected = if(pageManaging) key in selection else key == selectedBookKey
                                            onClick(if(pageManaging) "切换选择" else "打开书籍") { open(); true }
                                            if(selectionEnabled && key in selectableKeys) onLongClick("选择${saved.book.title}") { dragSelection.selectOne(key); true }
                                        }, selecting = pageManaging, selected = if(pageManaging) key in selection else key == selectedBookKey,
                                            enabled = !batchWorking && (!pageManaging || key in selectableKeys), pinned = saved.pinned,
                                            volumeLabel = if(saved.book.ref.isWenku) {
                                                if(filteringVolumes && group.volumes.size != volumes.size) "匹配 ${group.volumes.size} / ${volumes.size} 卷"
                                                else if(volumes.isEmpty()) "挂载分卷" else "本地 ${volumes.size} 卷"
                                            } else null,
                                            onVolumes = if(saved.book.ref.isWenku) ({
                                                focus.clearFocus()
                                                managing = false; selection = emptySet(); volumeMatches = if(filteringVolumes) group.volumes.map { it.book.ref.key }.toSet() else null
                                                volumeSheetKey = key
                                            }) else null, progressLabel = progress)
                                    } }
                                    repeat(columns - groupRow.size) { Spacer(Modifier.weight(1f)) }
                                }
                            } else items(reorder.rows, key = { it.saved.book.ref.key }, contentType = { if(it.parent == null) "book" else "volume" }) { row ->
                                val saved = row.saved
                                LaunchedEffect(active, saved.book.ref, profile?.username, state.syncStatus[profile?.username]?.lastSuccessAt) {
                                    if(!active || profile == null || saved.book.ref.isLocal || saved.book.ref.isWenku) return@LaunchedEffect
                                    try { c.refreshCloudReading(saved.book) }
                                    catch(e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch(_: Exception) { /* 离线时保留已知的本地和云端位置。 */ }
                                }
                                val dragging = reorder.draggedKey == saved.book.ref.key
                                val selectionColor = animateColorAsState(
                                    when {
                                        pageManaging && saved.book.ref.key in selection -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f)
                                        !pageManaging && saved.book.ref.key == selectedBookKey -> MaterialTheme.colorScheme.secondaryContainer
                                        else -> Color.Transparent
                                    },
                                    animationSpec = tween(if(reducedMotion) 0 else AppMotion.Release), label = "shelf-selection"
                                )
                                val selectable = saved.book.ref.key in selectableKeys
                                // 没有移动的长按松手也可能到达子项 clickable；由长按手势处理，不能再切换一次勾选。
                                val onOpen = {
                                    if(!dragSelection.dragging) {
                                        if(pageManaging) { if(selectable && !batchWorking) selection = if(saved.book.ref.key in selection) selection - saved.book.ref.key else selection + saved.book.ref.key }
                                        else { filtersExpanded = false; onOpenBook(saved.book.ref) }
                                    }
                                }
                                val position = state.positions[saved.book.ref.key]
                                val onRead: (() -> Unit)? = if(state.shelfTapToRead && row.parent == null && !pageManaging && position != null) ({
                                    if(!dragSelection.dragging && !batchWorking) {
                                        filtersExpanded = false
                                        c.read(saved.book.ref, position.chapterId)
                                    }
                                }) else null
                                val itemMotion = (if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = if(dragging || dragSelection.dragging) null else tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)))
                                    .semantics {
                                        onClick(if(pageManaging) "切换选择" else if(onRead != null) "继续阅读" else "打开书籍") { (onRead ?: onOpen)(); true }
                                        if(pageManaging) stateDescription = if(saved.book.ref.key in selection) "已选择" else "未选择"
                                        if(active && selectable && !batchWorking) onLongClick("选择${saved.book.title}") { dragSelection.selectOne(saved.book.ref.key); true }
                                    }
                                val trailing: @Composable () -> Unit = {
                                    if(pageManaging) AppCheckbox(saved.book.ref.key in selection, { checked ->
                                        if(!dragSelection.dragging) selection = if(checked) selection + saved.book.ref.key else selection - saved.book.ref.key
                                    }, enabled = selectable && !batchWorking) else AppIconButton(onClick = { if(!dragSelection.dragging) selected = saved }) { Icon(Icons.Outlined.MoreVert, "管理 ${saved.book.title}") }
                                }
                                if(row.parent != null) MountedVolumeRow(saved, state.positions[saved.book.ref.key],
                                    itemMotion.zIndex(if(dragging) 1f else 0f).graphicsLayer {
                                        translationY = if(dragging) reorder.offset else 0f
                                        shadowElevation = if(dragging && !reducedMotion) 4.dp.toPx() else 0f
                                    }.drawBehind { drawRect(selectionColor.value) }.semantics { if(!pageManaging && saved.book.ref.key == selectedBookKey) stateDescription = "已选中" }, onOpen, trailing,
                                    dragHandle = if(canReorder && reorder.siblings(saved.book.ref.key).size > 1) {
                                        { VolumeDragHandle(reorder, saved.book.ref.key, saved.book.title) }
                                    } else null)
                                else Column(itemMotion.testTag("shelf-book-${saved.book.ref.key}").drawBehind { drawRect(selectionColor.value) }
                                    .semantics { if(!pageManaging && saved.book.ref.key == selectedBookKey) stateDescription = "已选中" }) {
                                    BookRow(if(filterCharacters) saved.book.copy(totalCharacters = characterCounts.count(saved.book)) else saved.book, onOpen,
                                        status = bookRowStatus(saved.book, saved, state.positions[saved.book.ref.key], state.bookUpdates[saved.book.ref.key], profile?.username),
                                        showCharacterCount = filterCharacters, compactMetadata = page == 0, trailing = trailing,
                                        onRead = onRead)
                                    if(saved.book.ref.isWenku && (!pageManaging || dragSelection.dragging)) {
                                        val rotation by animateFloatAsState(if(row.expanded) 180f else 0f, tween(if(reducedMotion) 0 else AppMotion.Standard), label = "wenku-volume-disclosure")
                                        AppTextButton(onClick = {
                                            if(row.volumeCount == 0) volumeManager = saved
                                            else if(filteringVolumes) collapsedSearchGroups = if(row.expanded) collapsedSearchGroups + saved.book.ref.key else collapsedSearchGroups - saved.book.ref.key
                                            else c.store.update { it.copy(books = it.books.map { book -> if(book.book.ref == saved.book.ref) book.copy(volumesExpanded = !row.expanded) else book }) }
                                        }, enabled = !pageManaging, modifier = Modifier.padding(start = 20.dp).testTag("wenku-volumes-${saved.book.ref.key}")
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
                        if(pageManaging) selectionActions(page == 1, books.filter { it.book.ref.key in selection })
                    }
                    if(continueBook != null) {
                        val position = state.positions.getValue(continueBook.book.ref.key)
                        ContinueReadingButton(continueBook.book, position,
                            { c.read(continueBook.book.ref, position.chapterId) },
                            Modifier.align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 16.dp + with(density) { pagingControlsHeight.toDp() })
                                .onSizeChanged { continueHeight = it.height }, expanded = continueExpanded)
                    }
                }
            }
        }
    }
    val volumeGroup = volumeSheetKey?.let { allVolumeGroups[it] }
    LaunchedEffect(volumeSheetKey, volumeGroup == null) {
        if(volumeSheetKey != null && volumeGroup == null) { volumeSheetKey = null; managing = false; selection = emptySet() }
    }
    if(volumeGroup != null) WenkuVolumesSheet(volumeGroup.saved, volumeGroup.volumes, state.positions, volumeMatches,
        managing, selection, batchWorking, { managing = it }, { selection = it },
        onDismiss = { volumeSheetKey = null; managing = false; selection = emptySet() },
        onOpen = { volumeSheetKey = null; onOpenBook(it.book.ref) },
        onContinue = { saved, position -> volumeSheetKey = null; c.read(saved.book.ref, position.chapterId) },
        onDetails = { volumeSheetKey = null; onOpenBook(volumeGroup.saved.book.ref) },
        onMount = { volumeManager = volumeGroup.saved }, onManageBook = { selected = it }, onReorder = commitVolumeOrder,
        actions = { selectionActions(false, volumeGroup.volumes.filter { it.book.ref.key in selection }) })
    if(createFolder) TextPrompt("新建收藏夹", "名称", onDismiss = { createFolder = false }) { name -> c.action { c.store.update { it.createShelfFolder(name.trim()) } } }
    if(renameFolder) TextPrompt("重命名收藏夹", "名称", folder, { renameFolder = false }) { name -> val previous = folder; c.action { c.store.update { it.renameShelfFolder(previous, name.trim()) } } }
    if(deleteFolder) ConfirmDialog("删除收藏夹？", "其中的书籍会移入默认收藏，文件不会删除。", { deleteFolder = false }, confirmLabel = "删除收藏夹") { val previous = folder; c.action { c.store.update { it.deleteShelfFolder(previous) }; folderId = null } }
    batchRemoval?.let { refs ->
        val deletesCopies = state.deleteLocalCopyOnShelfRemoval && refs.any { it.isLocal }
        ConfirmDialog("移出 ${refs.size} 本书？", if(deletesCopies) "已开启移出书架时删除本地副本：所选本地小说的导入副本会同时删除，无法撤销。原文件、下载文件和阅读记录保留。未选中的分卷会解除挂载。"
            else "移出所选书籍，保留本地文件和阅读记录；完成后可撤销。未选中的分卷会解除挂载。", { batchRemoval = null }, confirmLabel = "移出书架") {
            batchWorking = true
            c.action {
                try {
                    val previous = c.store.state.value.books
                    val deleted = c.store.removeShelfBooks(refs)
                    managing = false; selection = emptySet(); batchWorking = false
                    if(deleted.isNotEmpty()) c.message("已移出 ${refs.size} 本书，并删除 ${deleted.size} 份本地副本")
                    else if(c.snackbar.showSnackbar("已移出 ${refs.size} 本书", actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                        c.store.update { restoreRemovedShelfBooks(it, previous, refs) }
                } finally { batchWorking = false }
            }
        }
    }
    batchDeletion?.let { refs ->
        ConfirmDialog("删除 ${refs.size} 本本地小说？", "删除所选小说的导入副本及对应下载任务和文件，并移出书架。导入前的原文件和阅读记录保留。此操作无法撤销。", { batchDeletion = null }, confirmLabel = "删除本地副本") {
            batchWorking = true
            c.action {
                try {
                    deleteBookFiles(c.app, refs, eraseReadingData = false)
                    managing = false; selection = emptySet()
                    c.message("已删除 ${refs.size} 本本地小说")
                } finally { batchWorking = false }
            }
        }
    }
    permanentDeletion?.let { refs ->
        ConfirmDialog("彻底删除 ${refs.size} 本书？", "移出所选书籍，删除其全部本地分卷、导入副本、对应下载任务和文件，以及本机阅读记录、笔记和单书设置。正在下载的任务会取消。导入前的原文件和原站收藏不受影响，此操作无法撤销。",
            { permanentDeletion = null }, confirmLabel = "彻底删除") {
            batchWorking = true
            c.action("书籍及关联文件已彻底删除") {
                try { deleteBookFiles(c.app, refs); managing = false; selection = emptySet() }
                finally { batchWorking = false }
            }
        }
    }
    if(bulkMove) AppAlertDialog(onDismissRequest = { bulkMove = false }, title = { Text("移入收藏夹") }, text = { AppScrollColumn {
        if(state.books.any { it.book.ref.key in selection && it.parentWenkuKey != null && it.parentWenkuKey !in selection }) Text("单独移动分卷会取消其挂载；同时移动所属文库可保留挂载。", style = MaterialTheme.typography.bodySmall)
        state.folders.forEach { target -> AppTextButton(onClick = { c.store.update { it.moveShelfBooks(selection, target) }; bulkMove = false; managing = false; selection = emptySet() }) { Text(target) } }
    } }, confirmButton = {})
    if(bulkStatus) AppAlertDialog(onDismissRequest = { bulkStatus = false }, title = { Text("修改 ${selection.size} 本的阅读状态") }, text = { Column {
        Text("手动选择后固定状态，自动阅读不会覆盖。", style = MaterialTheme.typography.bodySmall)
        readingStatuses.forEach { status -> AppTextButton(onClick = {
            c.store.update { it.withReadingStatus(selection, status) }
            bulkStatus = false; managing = false; selection = emptySet()
        }) { Text(status) } }
        AppTextButton(onClick = {
            c.store.update { it.withAutomaticReadingStatus(selection) }
            bulkStatus = false; managing = false; selection = emptySet()
        }) { Text("恢复自动管理") }
    } }, confirmButton = {})
    renamingDocument?.let { saved -> TextPrompt("重命名本地小说", "名称", saved.book.title, { renamingDocument = null }) { name ->
        c.action("小说已重命名") { withContext(Dispatchers.IO) { c.store.renameDocument(saved.book.ref.id, name) } }
    } }
    selected?.let { saved -> AppSheet(onDismissRequest = { selected = null }) { AppScrollColumn(modifier = Modifier.navigationBarsPadding(), contentModifier = Modifier.padding(bottom = 28.dp)) {
        Text(saved.book.title, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2)
        if(saved.hasUpdates) MenuRow("标记更新已读", "清除本书的更新提示", Icons.Outlined.DoneAll, { c.store.update { it.withAcknowledgedBookUpdates(saved.book.ref) }; selected = null })
        MenuRow(if(saved.pinned) "取消置顶" else "置顶", "在书架顶部显示", Icons.Outlined.PushPin, { c.store.update { it.copy(books = it.books.map { b -> if(b.book.ref == saved.book.ref) b.copy(pinned = !b.pinned) else b }) }; selected = null })
        ChoiceRow("阅读状态", readingStatuses, readingStatuses.indexOf(saved.status)) { status -> c.store.update { it.withReadingStatus(setOf(saved.book.ref.key), readingStatuses[status]) }; selected = null }
        if(saved.readingStatusControl?.automatic != true) MenuRow("恢复自动管理", "当前为手动设置或旧版保留状态，自动阅读不会覆盖", Icons.Outlined.Sync, {
            c.store.update { it.withAutomaticReadingStatus(setOf(saved.book.ref.key)) }; selected = null
        }) else Text(if(state.autoReadingStatus) "阅读状态由自动管理；手动选择后将固定状态" else "自动记录已关闭，当前状态保留",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        val parent = state.books.firstOrNull { it.book.ref.isWenku && it.book.ref.key == saved.parentWenkuKey }
        if(parent == null) ChoiceRow("收藏夹", state.folders, state.folders.indexOf(saved.folder)) { index -> c.store.saveBook(saved.book, state.folders[index]); selected = null }
        else Text("所属收藏夹：${parent.folder}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if(saved.book.ref.isWenku) MenuRow("管理挂载分卷", "选择已导入的分卷，在本书下方展开阅读", Icons.Outlined.LibraryAdd, { selected = null; volumeManager = saved })
        if(saved.book.ref.isWenku && state.books.any { it.parentWenkuKey == saved.book.ref.key }) WenkuSiteOrderActions(c, saved.book.ref.key)
        if(saved.book.ref.isLocal) MenuRow(if(parent == null) "挂载到文库小说" else "更换或取消挂载", parent?.let { "当前挂载：${it.book.title}" } ?: "归入指定的文库收藏", Icons.Outlined.DriveFileMove, { selected = null; volumeParentPicker = saved })
        if(saved.book.ref.isLocal) MenuRow("重命名", "修改本地小说或分卷名称", Icons.Outlined.Edit, { selected = null; renamingDocument = saved })
        if(saved.book.ref.isLocal) MenuRow("本地术语表", "维护此文件的专有名词", Icons.Outlined.Translate, { selected = null; c.go("glossary/${saved.book.ref.key}") })
        if(saved.book.ref.isLocal) MenuRow("导出原文件", "导出保存在本机的书籍文件", Icons.Outlined.IosShare, { c.action {
            val export = withContext(Dispatchers.IO) { prepareLocalBookExport(c.store, saved.book.ref.id) }
            localExportId = saved.book.ref.id
            localExportOriginal = export.original
            selected = null
            sourceExporter.launch(export.fileName)
        } })
        MenuRow("移出书架", if(saved.book.ref.isLocal && state.deleteLocalCopyOnShelfRemoval) "同时删除导入副本，无法撤销；原文件不受影响" else "保留文件和阅读记录，移除后可撤销", Icons.Outlined.RemoveCircleOutline, {
            val previous = c.store.state.value.books
            selected = null
            c.action {
                val deleted = c.store.removeShelfBook(saved.book.ref)
                if(deleted) { c.message("已移出书架并删除「${saved.book.title}」的导入副本"); return@action }
                if(c.snackbar.showSnackbar("已将「${saved.book.title}」移出书架", actionLabel = "撤销", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                    c.store.update { current ->
                        restoreRemovedShelfBook(current, previous, saved.book.ref)
                    }
                }
            }
        })
        if(saved.book.ref.isLocal) MenuRow("删除本地小说", "删除导入副本及对应下载，保留阅读记录", Icons.Outlined.DeleteOutline, { selected = null; deletingDocument = saved })
        MenuRow("彻底删除", if(saved.book.ref.isWenku) "移出书架并删除全部分卷、对应下载和阅读资料" else "移出书架并删除副本、对应下载和阅读资料", Icons.Outlined.DeleteForever, {
            selected = null; permanentDeletion = setOf(saved.book.ref)
        })
    } } }
    volumeManager?.let { parent -> WenkuVolumeManager(parent, state.books, { volumeManager = null }) { keys ->
        c.store.update { it.withWenkuVolumes(parent.book.ref.key, keys) }
        volumeManager = null
    } }
    volumeParentPicker?.let { volume -> VolumeParentPicker(volume, state.books, { volumeParentPicker = null }) { parentKey ->
        c.store.update { it.withVolumeParent(volume.book.ref.key, parentKey) }
        volumeParentPicker = null
    } }
    deletingDocument?.let { saved -> ConfirmDialog("删除「${saved.book.title}」？", "将删除导入副本及对应下载任务和文件，并移出书架。导入前的原文件和阅读记录保留。此操作无法撤销。", { deletingDocument = null }, confirmLabel = "删除本地小说") {
        c.action("本地小说及对应下载已删除") { deleteBookFiles(c.app, setOf(saved.book.ref), eraseReadingData = false) }
    } }
}
