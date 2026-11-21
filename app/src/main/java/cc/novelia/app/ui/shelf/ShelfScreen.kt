@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import android.net.Uri
import androidx.activity.compose.BackHandler
import cc.novelia.app.ui.components.base.AppCheckbox
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.data.updates.withAcknowledgedBookUpdates
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.moveShelfBooks
import cc.novelia.app.data.library.CUSTOM_SHELF_SORT
import cc.novelia.app.data.library.withShelfSort
import cc.novelia.app.data.library.withShelfBookMoved
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.ui.components.base.CharacterCountFilterSaver
import cc.novelia.app.data.library.ShelfGroup
import cc.novelia.app.data.library.ShelfBookType
import cc.novelia.app.data.library.readingStatuses
import cc.novelia.app.data.library.shelfGroups
import cc.novelia.app.data.library.withReadingStatus
import cc.novelia.app.data.library.withAutomaticReadingStatus
import cc.novelia.app.data.library.withVolumeParent
import cc.novelia.app.data.library.withMountedVolumes
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DEFAULT_FOLDER_ID
import cc.novelia.app.ui.downloads.bookDownloadEntry
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.createShelfFolder
import cc.novelia.app.data.model.renameShelfFolder
import cc.novelia.app.data.model.deleteShelfFolder
import cc.novelia.app.data.model.localFavoriteFolders
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.restoredShelfLocation
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppDropdownMenu
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
import cc.novelia.app.ui.navigation.applyFavoriteSync
import cc.novelia.app.ui.navigation.previewFavoriteSync
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
    var favoriteSync by rememberSaveable(profile?.username) { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(state.restoredShelfLocation().tab) }
    var folderId by rememberSaveable { mutableStateOf(state.restoredShelfLocation().localFolderId) }
    val folder = state.folders.firstOrNull { state.folderIds[it] == folderId } ?: "全部"
    val folderChoices = remember(state.folders, state.folderIds, state.localFavoriteFolderOrder) {
        state.localFavoriteFolderOrder.ordered(state.localFavoriteFolders(), DEFAULT_FOLDER_ID)
    }
    val orderedFolders = remember(folderChoices) { folderChoices.map(Folder::title) }
    var query by rememberSaveable { mutableStateOf("") }
    val sort = state.shelfSort
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
    var addToShelf by remember(tab, managing) { mutableStateOf(false) }
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
    val recent = remember(state.books, state.positions) {
        state.books.asSequence().filter { state.positions.containsKey(it.book.ref.key) }.maxByOrNull { state.positions.getValue(it.book.ref.key).updatedAt }
    }
    val settledQuery = rememberDebouncedQuery(query)
    val filteringVolumes = settledQuery.isNotBlank() || readingStatus != "全部"
    val reducedMotion = appReducedMotion()
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val shelfGroups = remember(state.books, state.positions, state.shelfBookOrder, folder, settledQuery, sort, bookType, readingStatus) {
        state.shelfGroups(localOnly = false, folder = folder, query = settledQuery, sort = sort, type = bookType, status = readingStatus)
    }
    val filterShelfCharacters = bookType == ShelfBookType.Web && characterFilter.active
    val countCandidates = if(bookType == ShelfBookType.Web) shelfGroups.map { it.saved.book } else emptyList()
    // 字数结果留在分页器外，离开书架分区后返回时无需重新补全。
    val characterCounts = rememberShelfCharacterCounts(c, countCandidates, tab == 0 && filterShelfCharacters, profile?.username, cacheGeneration)
    LaunchedEffect(tab) { managing = false; selection = emptySet() }
    val allVolumeGroups = remember(state.books) {
        state.shelfGroups(localOnly = false, folder = "全部", query = "", sort = 2).associateBy { it.saved.book.ref.key }
    }
    fun volumeLabel(group: ShelfGroup): String {
        val total = allVolumeGroups[group.saved.book.ref.key]?.volumes?.size ?: 0
        return if(filteringVolumes && group.volumes.size != total) "匹配 ${group.volumes.size} / $total 卷" else "本地 $total 卷"
    }
    fun fileAuthors(saved: SavedBook): List<String> = saved.book.authors.filter(String::isNotBlank).ifEmpty {
        allVolumeGroups[saved.parentWenkuKey]?.saved?.book?.authors.orEmpty().filter(String::isNotBlank)
    }
    val incompleteLocalMetadata = remember(state.books) {
        state.books.map { it.book }.filter {
            it.ref.isLocal && (it.localChapterCount == null ||
                (it.subtitle.substringBefore(" · ") == "EPUB" && it.authors.none(String::isNotBlank)))
        }
    }
    LaunchedEffect(c.store, incompleteLocalMetadata) { c.store.restoreLocalShelfMetadata(incompleteLocalMetadata) }
    val selectionActions: @Composable (Boolean, List<SavedBook>) -> Unit = { localFiles, chosen ->
        val downloadable = chosen.filter { it.book.ref.isWebNovel }
        ShelfSelectionActions(chosen.size, localFiles, batchWorking, queueingDownloads,
            canDownload = downloadable.isNotEmpty(), onMove = { bulkMove = true }, onStatus = { bulkStatus = true },
            onDownload = {
                queueingDownloads = true
                c.action {
                    try {
                        val reader = state.reader
                        downloadable.forEach { saved ->
                            val entry = bookDownloadEntry(c.api, saved.book, null, reader.mode, reader.engines, reader.parallel,
                                "epub", "${saved.book.title}.epub")
                            cc.novelia.app.files.DownloadWorker.enqueue(c.app, entry)
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
        AppIconButton(onClick = { if(profile == null) c.go("login") else favoriteSync = true },
            enabled = !batchWorking && !cloudBusy) { Icon(Icons.Outlined.CloudSync, "云端与本地收藏同步") }
        AppIconButton(onClick = { c.go("updates") }) { Icon(Icons.Outlined.NewReleases, "查看书架更新") }
        AppIconButton(onClick = { c.go("history") }) { Icon(Icons.Outlined.History, "阅读历史") }
        val showAdd = tab == 0 && !managing
        Box {
            AppIconButton(onClick = { if(showAdd) addToShelf = true else importLocalFiles() },
                enabled = if(showAdd) !batchWorking else !importing,
                modifier = Modifier.semantics { contentDescription = if(showAdd) "添加到书架" else "导入本地文件" }) {
                val icon = if(showAdd) Icons.Outlined.Add else Icons.Outlined.FileOpen
                if(reducedMotion) Icon(icon, null)
                else Crossfade(icon, animationSpec = tween(AppMotion.Quick), label = "书架操作") { Icon(it, null) }
            }
            AppDropdownMenu(addToShelf, { addToShelf = false }) {
                DropdownMenuItem({ Text("导入本地文件") }, { addToShelf = false; importLocalFiles() },
                    enabled = !importing, leadingIcon = { Icon(Icons.Outlined.FileOpen, null) })
                DropdownMenuItem({ Text("新建本地总集") }, { addToShelf = false; c.editLocalSeries(folderId = folderId) },
                    leadingIcon = { Icon(Icons.Outlined.LibraryAdd, null) })
            }
        }
    }) { padding ->
        ShelfPager(tab, { tab = it }, enabled = !batchWorking && !cloudBusy, modifier = Modifier.padding(padding),
            controls = {
                DocumentPickerFallback(importer, enabled = !importing)
                DocumentImportPanel(importModel, c::book)
            }) { page ->
            val active = page == tab
            if(page == 2) CloudShelf(c, onOpenBook, selectedBookKey, active = active, observeInvalidation = !favoriteSync,
                onSelectionStateChanged = updateCloudSelection) else BoxWithConstraints(Modifier.fillMaxSize()) {
                // 邻页预组合时按自己的分区生成列表，不能复用当前选中页的数据或修剪其批量选择。
                val baseGroups = if(page == 0) shelfGroups else remember(state.books, state.positions, state.shelfBookOrder, folder, settledQuery, sort, fileType, readingStatus) {
                    state.shelfGroups(localOnly = true, folder = folder, query = settledQuery, sort = sort,
                        type = fileType, status = readingStatus)
                }
                val filterCharacters = page == 0 && filterShelfCharacters
                val groups = if(filterCharacters) baseGroups.filter { characterFilter.matches(characterCounts.count(it.saved.book)) } else baseGroups
                val books = remember(groups) { groups.filter { it.matchesFilters }.map { it.saved } }
                val grid = if(page == 0) state.shelfGrid else state.localFilesGrid
                val columns = shelfGridColumns(maxWidth, state.shelfGridDensity)
                val compactGridCards = compactShelfGridCards(maxWidth, columns)
                val gridRows = remember(groups, columns) { groups.chunked(columns) }
                val groupKeys = remember(groups) { groups.map { it.saved.book.ref.key } }
                val selectableKeys = remember(books) { books.map { it.book.ref.key }.toSet() }
                LaunchedEffect(active, selectableKeys) { if(active) selection = selection.intersect(selectableKeys) }
                val pageManaging = active && managing
                val toolsExpanded = state.shelfControlsExpanded
                val pageType = if(page == 0) bookType else fileType
                val layout = rememberShelfLayoutState(grid, groupKeys, columns)
                val listState = layout.current(grid)
                val showControlsToggle = !managing && !batchWorking
                val continueBook = recent?.takeIf { page == 0 && active && !pageManaging }
                val density = LocalDensity.current
                val collapseThreshold = with(density) { 48.dp.roundToPx() }
                val continueExpanded by remember(listState, collapseThreshold) {
                    derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < collapseThreshold }
                }
                var continueHeight by remember { mutableIntStateOf(0) }
                var pagingControlsHeight by remember(listState) { mutableIntStateOf(0) }
                var filtersExpanded by remember { mutableStateOf(false) }
                LaunchedEffect(active) { if(!active) filtersExpanded = false }
                val focus = LocalFocusManager.current
                var controlsHeight by remember { mutableIntStateOf(0) }
                val gridHit = rememberShelfGridHitTest(layout.grid, gridRows.map { groupRow -> groupRow.map { it.saved.book.ref.key } }, columns)
                val selectionEnabled = active && !batchWorking
                val reorderBooks = selectionEnabled && sort == CUSTOM_SHELF_SORT
                val reorderKeys = remember(reorderBooks, groupKeys, selectableKeys) {
                    if(reorderBooks) groupKeys.filter { it in selectableKeys } else emptyList()
                }
                val dragSelection = rememberShelfDragSelection(listState,
                    groupKeys.filter { it in selectableKeys },
                    selection, pageManaging, selectionEnabled,
                    onBegin = { managing = true; filtersExpanded = false; focus.clearFocus() }, onSelection = { selection = it.intersect(selectableKeys) },
                    hitTest = if(grid) gridHit else null, reorderKeys = reorderKeys,
                    onReorder = { key, target, after -> c.store.update { it.withShelfBookMoved(key, target, after) } })
                val dropColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .8f)
                val dropOutline = MaterialTheme.colorScheme.primary
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val filterHeight = maxHeight * .9f
                    Column(Modifier.fillMaxSize()) {
                        LocalShelfFilters(page == 1, pageType, { if(page == 0) bookType = it else fileType = it },
                            folderChoices, folder, { folderId = state.folderIds[it] }, sort,
                            { value -> c.action { c.store.updateAndFlush { it.withShelfSort(value) } } },
                            query, { query = it }, readingStatus, { readingStatus = it },
                            filtersExpanded, { filtersExpanded = it }, filterHeight, { createFolder = true }, { renameFolder = true }, { deleteFolder = true },
                            characters = characterFilter, onCharacters = { characterFilter = it },
                            countLabel = "${groups.size} 本" + groups.sumOf { it.volumes.size }.takeIf { it > 0 }?.let { " · $it 分卷" }.orEmpty(),
                            visible = !pageManaging, active = active, modifier = Modifier.onSizeChanged { controlsHeight = it.height },
                            grid = grid, onToggleLayout = { c.store.update { if(page == 0) it.copy(shelfGrid = !it.shelfGrid) else it.copy(localFilesGrid = !it.localFilesGrid) } },
                            folderOrder = state.localFavoriteFolderOrder, collapsed = !toolsExpanded,
                            onOrderFolders = { order -> c.store.update { it.copy(localFavoriteFolderOrder = order) } },
                            footer = {
                                if(showControlsToggle) ShelfControlsToggle(toolsExpanded,
                                    folder != "全部" || query.isNotBlank() || readingStatus != "全部" || pageType != ShelfBookType.All,
                                    enabled = active) {
                                    c.store.update { it.copy(shelfControlsExpanded = !it.shelfControlsExpanded) }
                                }
                            }) {
                            if(filterCharacters) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                val unknown = countCandidates.count { characterCounts.count(it) == null }
                                Text(if(characterCounts.loading) "正在补全字数…" else "$unknown 本字数未知", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                if(unknown > 0) AppTextButton(onClick = { characterCounts.loadMore(countCandidates) }, enabled = !characterCounts.loading) { Text("补全字数") }
                            }
                        }
                        if(pageManaging) ShelfSelectionHeader(selection.size, !batchWorking,
                            // 连续勾选时保持列表起点。自定义排序的浮层跟随手指，可直接收起工具栏。
                            if(dragSelection.dragging && dragSelection.draggedBook == null) with(density) { controlsHeight.toDp() } else 48.dp,
                            onSelectAll = { selection = selectableKeys },
                            onFinish = { managing = false; selection = emptySet() })
                        ShelfLayoutTransition(grid, Modifier.weight(1f)) { showGrid ->
                        AppLazyColumn(modifier = Modifier.fillMaxSize(),
                            listModifier = Modifier.testTag(if(showGrid) "shelf-books-grid" else "shelf-books")
                                .shelfDragSelection(dragSelection, selectionEnabled && showGrid == grid), state = layout.current(showGrid),
                            verticalArrangement = Arrangement.spacedBy(if(showGrid) 14.dp else 0.dp),
                            onPageButtonsHeightChanged = if(page == 0 && showGrid == grid) ({ pagingControlsHeight = it }) else null,
                            contentPadding = PaddingValues(top = if(showGrid) 8.dp else 0.dp, bottom = if(continueBook != null)
                                with(density) { continueHeight.toDp() } + 32.dp else 0.dp)) {
                            if(groups.isEmpty()) item {
                                if(query.isNotBlank() || folder != "全部" || readingStatus != "全部" || pageType != ShelfBookType.All)
                                    EmptyState("没有符合条件的小说", if(filterCharacters) "可以放宽字数范围、包含未知作品或继续补全字数。" else "试试其他书名、作者、类型或阅读状态。", action = "清除筛选", onAction = { query = ""; folderId = null; readingStatus = "全部"; bookType = ShelfBookType.All; fileType = ShelfBookType.All; characterFilter = CharacterCountFilter() })
                                else EmptyState(if(page == 1) "把故事装进口袋" else "书架等你来填满", if(page == 1) "支持 EPUB、TXT、SRT 和 CBZ，导入后即可离线阅读。" else "去发现喜欢的小说，或导入你已有的文件。", action = if(page == 1) "导入文件" else "去发现", onAction = { if(page == 1) importLocalFiles() else c.go("discover") }, sticker = Sticker.Welcome)
                            }
                            if(showGrid) items(gridRows, key = { it.first().saved.book.ref.key }, contentType = { "cover-row" }) { groupRow ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    groupRow.forEach { group -> key(group.saved.book.ref.key) {
                                        val saved = group.saved
                                        val key = saved.book.ref.key
                                        val volumes = allVolumeGroups[key]?.volumes.orEmpty()
                                        val recentVolume = volumes.filter { it.book.ref.key in state.positions }.maxByOrNull { state.positions.getValue(it.book.ref.key).updatedAt }
                                        val readingBook = recentVolume ?: saved
                                        val status = bookRowStatus(readingBook.book, readingBook, state.positions[readingBook.book.ref.key],
                                            state.bookUpdates[readingBook.book.ref.key], profile?.username)
                                        val open = { if(!dragSelection.dragging && !batchWorking) {
                                            if(pageManaging) { if(key in selectableKeys) selection = if(key in selection) selection - key else selection + key }
                                            else { filtersExpanded = false; onOpenBook(saved.book.ref) }
                                        } }
                                        LaunchedEffect(active, saved.book.ref, profile?.username, state.syncStatus[profile?.username]?.lastSuccessAt) {
                                            if(active && profile != null && saved.book.ref.isWebNovel) {
                                                try { c.refreshCloudReading(saved.book) }
                                                catch(e: kotlinx.coroutines.CancellationException) { throw e }
                                                catch(_: Exception) { /* 离线保留已有进度。 */ }
                                            }
                                        }
                                        ShelfCoverCard(saved.book, status, open, Modifier.weight(1f).testTag("shelf-book-$key").drawWithContent {
                                            drawContent()
                                            if(dragSelection.dropTarget == key) drawRect(dropOutline, style = Stroke(3.dp.toPx()))
                                        }.semantics {
                                            this.selected = if(pageManaging) key in selection else key == selectedBookKey
                                            onClick(if(pageManaging) "切换选择" else "打开书籍") { open(); true }
                                            if(selectionEnabled && key in selectableKeys) onLongClick("选择${saved.book.title}") { dragSelection.selectOne(key); true }
                                            customActions = dragSelection.reorderActions(key)
                                        }, selecting = pageManaging, selected = if(pageManaging) key in selection else key == selectedBookKey,
                                            enabled = !batchWorking && (!pageManaging || key in selectableKeys), compact = compactGridCards,
                                            style = state.shelfGridStyle)
                                    } }
                                    repeat(columns - groupRow.size) { Spacer(Modifier.weight(1f)) }
                                }
                            } else items(groups, key = { it.saved.book.ref.key }, contentType = { "book" }) { group ->
                                val saved = group.saved
                                LaunchedEffect(active, saved.book.ref, profile?.username, state.syncStatus[profile?.username]?.lastSuccessAt) {
                                    if(!active || profile == null || !saved.book.ref.isWebNovel) return@LaunchedEffect
                                    try { c.refreshCloudReading(saved.book) }
                                    catch(e: kotlinx.coroutines.CancellationException) { throw e }
                                    catch(_: Exception) { /* 离线时保留已知的本地和云端位置。 */ }
                                }
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
                                val onRead: (() -> Unit)? = if(state.shelfTapToRead && !saved.book.ref.isVolumeParent && !pageManaging && position != null) ({
                                    if(!dragSelection.dragging && !batchWorking) {
                                        filtersExpanded = false
                                        c.read(saved.book.ref, position.chapterId)
                                    }
                                }) else null
                                val itemMotion = (if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = if(dragSelection.dragging) null else tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)))
                                    .semantics {
                                        onClick(if(pageManaging) "切换选择" else if(onRead != null) "继续阅读" else "打开书籍") { (onRead ?: onOpen)(); true }
                                        if(pageManaging) stateDescription = if(saved.book.ref.key in selection) "已选择" else "未选择"
                                        if(active && selectable && !batchWorking) onLongClick("选择${saved.book.title}") { dragSelection.selectOne(saved.book.ref.key); true }
                                        customActions = dragSelection.reorderActions(saved.book.ref.key)
                                    }
                                val trailing: @Composable () -> Unit = {
                                    if(pageManaging) AppCheckbox(saved.book.ref.key in selection, { checked ->
                                        if(!dragSelection.dragging) selection = if(checked) selection + saved.book.ref.key else selection - saved.book.ref.key
                                    }, enabled = selectable && !batchWorking) else AppIconButton(onClick = { if(!dragSelection.dragging) selected = saved }) { Icon(Icons.Outlined.MoreVert, "管理 ${saved.book.title}") }
                                }
                                Column(itemMotion.testTag("shelf-book-${saved.book.ref.key}").drawBehind {
                                    drawRect(if(dragSelection.dropTarget == saved.book.ref.key) dropColor else selectionColor.value)
                                }
                                    .semantics { if(!pageManaging && saved.book.ref.key == selectedBookKey) stateDescription = "已选中" }) {
                                    val book = when {
                                        filterCharacters -> saved.book.copy(totalCharacters = characterCounts.count(saved.book))
                                        page == 1 -> saved.book.copy(authors = fileAuthors(saved))
                                        else -> saved.book
                                    }
                                    BookRow(book, onOpen,
                                        status = bookRowStatus(saved.book, saved, state.positions[saved.book.ref.key], state.bookUpdates[saved.book.ref.key], profile?.username),
                                        showCharacterCount = filterCharacters, compactMetadata = page == 0, trailing = trailing,
                                        showLocalAuthors = true,
                                        onRead = onRead)
                                    if(saved.book.ref.isVolumeParent && !pageManaging) AppTextButton(onOpen, Modifier.padding(start = 12.dp)) {
                                        Text(volumeLabel(group))
                                        Icon(Icons.Outlined.ChevronRight, "查看分卷", Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        if(showGrid == grid) dragSelection.draggedBook?.let { key ->
                            groups.firstOrNull { it.saved.book.ref.key == key }?.let { ShelfReorderPreview(it.saved.book.title, dragSelection) }
                        }
                        }
                        if(pageManaging) selectionActions(page == 1, books.filter { it.book.ref.key in selection })
                    }
                    if(page == 0) {
                        ContinueReadingOverlay(continueBook?.let { it.book to state.positions.getValue(it.book.ref.key) },
                            { book, position -> c.read(book.ref, position.chapterId) },
                            Modifier.align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 16.dp + with(density) { pagingControlsHeight.toDp() })
                                .onSizeChanged { continueHeight = it.height }, expanded = continueExpanded)
                    }
                }
            }
        }
    }
    if(favoriteSync) {
        val binding = c.session.capture()
        binding.account?.let { account -> key(binding) {
            FavoriteSyncSheet(account, onCheck = { priority, status -> c.previewFavoriteSync(binding, priority, status) },
                onApply = { plan, status, progress -> c.applyFavoriteSync(binding, plan, status, progress) },
                onDismiss = { favoriteSync = false }, onFinished = { favoriteSync = false; c.message(it) })
        } }
    }
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
        val onlySeries = refs.all { it.isLocalSeries }
        val seriesMessage = "本地总集解散后，未单独选中删除的分卷会回到书架，保留文件和阅读进度。"
        val message = if(onlySeries) seriesMessage else "移出所选书籍，删除其关联的本地分卷、导入副本、下载及本机阅读资料。导入前的原文件和原站收藏不受影响，此操作无法撤销。" +
            if(refs.any { it.isLocalSeries }) "\n\n$seriesMessage" else ""
        ConfirmDialog(if(onlySeries) "解散 ${refs.size} 个总集？" else "彻底删除 ${refs.size} 本书？", message,
            { permanentDeletion = null }, confirmLabel = if(onlySeries) "解散总集" else "彻底删除") {
            batchWorking = true
            c.action(if(onlySeries) "总集已解散" else "所选书籍已处理") {
                try { deleteBookFiles(c.app, refs); managing = false; selection = emptySet() }
                finally { batchWorking = false }
            }
        }
    }
    if(bulkMove) AppAlertDialog(onDismissRequest = { bulkMove = false }, title = { Text("移入收藏夹") }, text = { AppScrollColumn {
        if(state.books.any { it.book.ref.key in selection && it.parentWenkuKey != null && it.parentWenkuKey !in selection }) Text("单独移动分卷会取消挂载。同时移动父项可保留挂载。", style = MaterialTheme.typography.bodySmall)
        orderedFolders.forEach { target -> AppTextButton(onClick = { c.store.update { it.moveShelfBooks(selection, target) }; bulkMove = false; managing = false; selection = emptySet() }) { Text(target) } }
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
        if(saved.book.ref.isLocal) MenuRow("书籍详情", "查看阅读时长和管理封面", Icons.Outlined.Info, {
            selected = null; c.showBookDetails(saved.book.ref)
        })
        if(saved.book.ref.isLocalSeries) MenuRow("编辑总集", "修改资料和分卷", Icons.Outlined.Edit, {
            selected = null; c.editLocalSeries(saved.book.ref)
        })
        if(saved.hasUpdates) MenuRow("标记更新已读", "清除本书的更新提示", Icons.Outlined.DoneAll, { c.store.update { it.withAcknowledgedBookUpdates(saved.book.ref) }; selected = null })
        MenuRow(if(saved.pinned) "取消置顶" else "置顶", "在书架顶部显示", Icons.Outlined.PushPin, { c.store.update { it.copy(books = it.books.map { b -> if(b.book.ref == saved.book.ref) b.copy(pinned = !b.pinned) else b }) }; selected = null })
        ChoiceRow("阅读状态", readingStatuses, readingStatuses.indexOf(saved.status)) { status -> c.store.update { it.withReadingStatus(setOf(saved.book.ref.key), readingStatuses[status]) }; selected = null }
        if(saved.readingStatusControl?.automatic != true) MenuRow("恢复自动管理", "当前为手动设置或旧版保留状态，自动阅读不会覆盖", Icons.Outlined.Sync, {
            c.store.update { it.withAutomaticReadingStatus(setOf(saved.book.ref.key)) }; selected = null
        }) else Text(if(state.autoReadingStatus) "阅读状态由自动管理；手动选择后将固定状态" else "自动记录已关闭，当前状态保留",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        val parent = state.books.firstOrNull { it.book.ref.isVolumeParent && it.book.ref.key == saved.parentWenkuKey }
        if(parent != null) Text("所属收藏夹：${parent.folder}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        else if(!saved.book.ref.isLocalSeries) ChoiceRow("收藏夹", orderedFolders, orderedFolders.indexOf(saved.folder)) { index -> c.store.saveBook(saved.book, orderedFolders[index]); selected = null }
        if(saved.book.ref.isWenku) MenuRow("管理挂载分卷", "选择已导入的书籍作为分卷", Icons.Outlined.LibraryAdd, { selected = null; volumeManager = saved })
        if(saved.book.ref.isWenku && state.books.any { it.parentWenkuKey == saved.book.ref.key }) WenkuSiteOrderActions(c, saved.book.ref.key)
        if(saved.book.ref.isLocal) MenuRow(if(parent == null) "挂载到文库或总集" else "更换或取消挂载", parent?.let { "当前挂载：${it.book.title}" } ?: "将本书添加为分卷", Icons.Outlined.DriveFileMove, { selected = null; volumeParentPicker = saved })
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
        if(!saved.book.ref.isLocalSeries) MenuRow("彻底删除", if(saved.book.ref.isWenku) "移出书架并删除全部分卷、对应下载和阅读资料" else "移出书架并删除副本、对应下载和阅读资料", Icons.Outlined.DeleteForever, {
            selected = null; permanentDeletion = setOf(saved.book.ref)
        })
    } } }
    volumeManager?.let { parent -> WenkuVolumeManager(parent, state.books, { volumeManager = null }) { keys ->
        c.action { c.store.update { it.withMountedVolumes(parent.book.ref.key, keys) }; volumeManager = null }
    } }
    volumeParentPicker?.let { volume -> VolumeParentPicker(volume, state.books, { volumeParentPicker = null }) { parentKey ->
        c.action { c.store.update { it.withVolumeParent(volume.book.ref.key, parentKey) }; volumeParentPicker = null }
    } }
    deletingDocument?.let { saved -> ConfirmDialog("删除「${saved.book.title}」？", "将删除导入副本及对应下载任务和文件，并移出书架。导入前的原文件和阅读记录保留。此操作无法撤销。", { deletingDocument = null }, confirmLabel = "删除本地小说") {
        c.action("本地小说及对应下载已删除") { deleteBookFiles(c.app, setOf(saved.book.ref), eraseReadingData = false) }
    } }
}
