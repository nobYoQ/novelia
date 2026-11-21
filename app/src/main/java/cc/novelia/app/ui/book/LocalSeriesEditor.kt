package cc.novelia.app.ui.book

import androidx.compose.foundation.shape.RoundedCornerShape
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import cc.novelia.app.data.library.LOCAL_SERIES_DESCRIPTION_LIMIT
import cc.novelia.app.data.library.LOCAL_SERIES_LIST_FIELD_LIMIT
import cc.novelia.app.data.library.LOCAL_SERIES_TITLE_LIMIT
import cc.novelia.app.data.library.LocalSeriesEdits
import cc.novelia.app.data.library.acceptsMountedVolume
import cc.novelia.app.data.library.compareVolumeTitles
import cc.novelia.app.data.library.localSeriesTagLength
import cc.novelia.app.data.library.mountedVolumes
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.book.BookCover
import cc.novelia.app.ui.components.book.rememberCustomBookCover
import cc.novelia.app.ui.components.documents.DocumentPickerTypes
import cc.novelia.app.ui.discover.KeywordLibrarySelectionDialog
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.LOCAL_SERIES_EDITOR_ROUTE
import cc.novelia.app.ui.navigation.NEW_LOCAL_SERIES
import cc.novelia.app.ui.theme.appRoundedCornerShape

internal fun NavGraphBuilder.localSeriesEditorDestination(c: AppController) {
    composable(LOCAL_SERIES_EDITOR_ROUTE, arguments = listOf(
        navArgument("folderId") { type = NavType.StringType; nullable = true; defaultValue = null },
        navArgument("volumes") { type = NavType.BoolType; defaultValue = false },
        navArgument("comic") { type = NavType.BoolType; defaultValue = false },
    )) { entry ->
        val id = requireNotNull(entry.arguments?.getString("id"))
        val creating = id == NEW_LOCAL_SERIES
        val seriesId = rememberSaveable(id) { if(creating) BookRef.newLocalSeries().id else id }
        val ref = remember(seriesId) { BookRef("local-series", seriesId) }
        LocalSeriesEditor(c, ref, creating, entry.arguments?.getString("folderId"), entry.arguments?.getBoolean("volumes") == true,
            entry.arguments?.getBoolean("comic") == true)
    }
}

@Composable private fun LocalSeriesEditor(c: AppController, ref: BookRef, creating: Boolean, initialFolderId: String?,
    startAtVolumes: Boolean, createComic: Boolean,
) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val parent = remember(state.books, ref) { state.books.firstOrNull { it.book.ref == ref } }
    if(creating && parent != null) {
        LaunchedEffect(ref) { c.selectCreatedSeries(ref) }
        return
    }
    if(!creating && parent == null) {
        Screen("编辑总集", c::back) { padding ->
            Box(Modifier.padding(padding)) { EmptyState("总集已不在书架", "可以返回书架查看其他总集。") }
        }
        return
    }
    val originalVolumes = remember(state.books, parent) { parent?.let { state.mountedVolumes(it) }.orEmpty().map { it.book.ref.key } }
    val folders = remember(state.folders, state.folderIds, state.localFavoriteFolderOrder) {
        state.localFavoriteFolderOrder.ordered(state.localFavoriteFolders(), DEFAULT_FOLDER_ID)
    }
    val medium = parent?.book?.shelfMedium ?: if (createComic) BookMedium.Comic else BookMedium.Novel
    val comic = medium == BookMedium.Comic
    val imported = remember(state.books, parent, medium) { state.books.filter { saved ->
        parent?.acceptsMountedVolume(saved) ?: (saved.book.ref.isLocal && saved.book.shelfMedium == medium)
    } }
    val booksByKey = remember(imported) { imported.associateBy { it.book.ref.key } }
    val parentsByKey = remember(state.books) { state.books.filter { it.book.ref.isVolumeParent }.associateBy { it.book.ref.key } }
    val initialFolder = parent?.folderId ?: initialFolderId ?: DEFAULT_FOLDER_ID
    val savedCover = rememberCustomBookCover(ref)
    val originalAuthors = parent?.book?.authors.orEmpty().joinToString("、")
    val originalTags = parent?.book?.tags.orEmpty()
    var title by rememberSaveable { mutableStateOf(parent?.book?.title.orEmpty()) }
    var authors by rememberSaveable { mutableStateOf(originalAuthors) }
    var tags by rememberSaveable { mutableStateOf(ArrayList(originalTags)) }
    var choosingTags by rememberSaveable { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf(parent?.seriesDescription.orEmpty()) }
    var folderId by rememberSaveable { mutableStateOf(initialFolder) }
    val selectedFolderId = folderId.takeIf { id -> folders.any { it.id == id } } ?: parent?.folderId ?: DEFAULT_FOLDER_ID
    var volumeKeys by rememberSaveable { mutableStateOf(ArrayList(originalVolumes)) }
    val volumes = remember(volumeKeys, booksByKey) { volumeKeys.mapNotNull(booksByKey::get) }
    val selectedKeys = remember(volumes) { volumes.map { it.book.ref.key } }
    val volumeSet = remember(selectedKeys) { selectedKeys.toSet() }
    var browsing by rememberSaveable { mutableStateOf(startAtVolumes && originalVolumes.isEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = remember(browsing, query, imported) {
        val keyword = query.trim()
        if(!browsing) emptyList() else imported.filter { saved ->
            saved.book.title.contains(keyword, ignoreCase = true) || saved.book.authors.any { it.contains(keyword, ignoreCase = true) }
        }.sortedWith { a, b -> compareVolumeTitles(a.book.title, b.book.title) }
    }
    var coverUri by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreCover by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null && !saving) {
            coverUri = uri.toString()
            restoreCover = false
        }
    }
    val changed = title != parent?.book?.title.orEmpty() || authors != originalAuthors || tags != originalTags ||
        description != parent?.seriesDescription.orEmpty() || selectedFolderId != initialFolder ||
        selectedKeys != originalVolumes || coverUri != null || restoreCover
    val goBack: () -> Unit = { if(!saving) { if(changed) discard = true else c.back() } }
    BackHandler(onBack = goBack)
    val focus = LocalFocusManager.current
    val preview = (parent?.book ?: BookCard(ref, "")).copy(title = title.trim().ifEmpty { "本地总集" }, cover = coverUri)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if(startAtVolumes) 2 else 0)

    val hasCustomCover = coverUri != null || (savedCover != null && !restoreCover)
    val keywordIndex by c.app.keywords.displayIndex.collectAsStateWithLifecycle()

    Screen(if(!creating) "编辑总集" else if (comic) "新建漫画总集" else "新建本地总集", goBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
                AppLazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), listModifier = Modifier.testTag("local-series-editor")) {
                    item("details") {
                        ProfileDetailCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                LocalSeriesCoverField(preview, coverUri == null && !restoreCover, hasCustomCover, !saving,
                                    onPick = { picker.launch(DocumentPickerTypes.Images.toTypedArray()) },
                                    onRestore = { coverUri = null; restoreCover = savedCover != null })
                                AppTextField(title, { title = it.take(LOCAL_SERIES_TITLE_LIMIT) }, label = { Text("总集名称") },
                                    modifier = Modifier.fillMaxWidth().testTag("local-series-title"), enabled = !saving,
                                    singleLine = true)
                                LocalSeriesFolderField(folders, selectedFolderId, !saving) { folderId = it }
                            }
                        }
                    }
                    item("metadata") {
                        ProfileDetailCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("补充资料（选填）", style = MaterialTheme.typography.titleMedium)
                                AppTextField(authors, { authors = it.take(LOCAL_SERIES_LIST_FIELD_LIMIT) }, label = { Text("作者") },
                                    modifier = Modifier.fillMaxWidth().testTag("local-series-authors"), enabled = !saving,
                                    singleLine = true)
                                LocalSeriesTagsField(tags, keywordIndex.labels, !saving, onChoose = {
                                    focus.clearFocus()
                                    choosingTags = true
                                }, onRemove = { tags = ArrayList(tags - it) })
                                AppTextField(description, { description = it.take(LOCAL_SERIES_DESCRIPTION_LIMIT) }, label = { Text("简介") },
                                    modifier = Modifier.fillMaxWidth().testTag("local-series-description"), enabled = !saving,
                                    minLines = 2, maxLines = 6)
                            }
                        }
                    }
                    item("volume-header") {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${if (comic) "话数" else "分卷"} · ${volumes.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            AppFilledTonalButton(enabled = !saving, onClick = { focus.clearFocus(); browsing = !browsing }) {
                                Icon(if(browsing) Icons.Outlined.Check else Icons.Outlined.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if(browsing) "完成选择" else if (comic) "添加单话" else "添加分卷")
                            }
                        }
                    }
                    if(browsing) {
                        item("volume-search") {
                            AppTextField(query, { query = it }, label = { Text(if (comic) "搜索已导入的漫画" else "搜索已导入的书籍") },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) }, enabled = !saving, singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("local-series-volume-search"))
                        }
                        if(candidates.isEmpty()) item("no-candidates") {
                            LocalSeriesVolumeEmpty(if (comic) {
                                if (imported.isEmpty()) "书架里还没有导入的漫画。" else "没有匹配的漫画。"
                            } else if(imported.isEmpty()) "书架里还没有导入的书籍。" else "没有匹配的书籍。")
                        }
                        items(candidates, key = { "pick-${it.book.ref.key}" }) { saved ->
                            val key = saved.book.ref.key
                            val selected = key in volumeSet
                            val otherParent = parentsByKey[saved.parentWenkuKey]?.takeIf { it.book.ref != ref }
                            val subtitle = otherParent?.let { if(selected) "保存后从 ${it.book.title} 移入本总集" else "已挂载到 ${it.book.title}" }
                            LocalSeriesVolumeItem(saved.book, subtitle,
                                Modifier.testTag("pick-series-volume-$key").toggleable(selected, enabled = !saving, role = Role.Checkbox,
                                    onValueChange = { checked -> volumeKeys = ArrayList(if(checked) selectedKeys + key else selectedKeys - key) }),
                                trailing = { AppCheckbox(selected, null, enabled = !saving) })
                        }
                    } else {
                        if(volumes.isEmpty()) item("no-volumes") {
                            LocalSeriesVolumeEmpty(if (comic) "可以把已导入的漫画挂载为单话。" else "可以从已导入的书籍中选择分卷。")
                        }
                        itemsIndexed(volumes, key = { _, saved -> saved.book.ref.key }) { index, saved ->
                            val key = saved.book.ref.key
                            fun move(to: Int) {
                                volumeKeys = ArrayList(selectedKeys).apply { add(to, removeAt(index)) }
                            }
                            LocalSeriesVolumeItem(saved.book, "第 ${index + 1} ${if (comic) "话" else "卷"}", Modifier.testTag("edit-series-volume-$key"), trailing = {
                                Row {
                                    AppIconButton({ move(index - 1) }, enabled = !saving && index > 0) {
                                        Icon(Icons.Outlined.KeyboardArrowUp, "上移 ${saved.book.title}")
                                    }
                                    AppIconButton({ move(index + 1) }, enabled = !saving && index < volumes.lastIndex) {
                                        Icon(Icons.Outlined.KeyboardArrowDown, "下移 ${saved.book.title}")
                                    }
                                    AppIconButton({ volumeKeys = ArrayList(selectedKeys - key) }, enabled = !saving) {
                                        Icon(Icons.Outlined.RemoveCircleOutline, "移出 ${saved.book.title}")
                                    }
                                }
                            })
                        }
                    }
                }
                Surface {
                    Column {
                        HorizontalDivider()
                        AppButton(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
                            enabled = !saving && title.isNotBlank() && (creating || changed), onClick = {
                                focus.clearFocus()
                                val edits = LocalSeriesEdits(title, authors, tags, description, selectedFolderId, selectedKeys, medium)
                                val uri = coverUri?.let(Uri::parse)
                                saving = true
                                c.action {
                                    try {
                                        c.store.saveLocalSeries(ref, creating, edits, uri?.let { selected ->
                                            { requireNotNull(c.app.contentResolver.openInputStream(selected)) { "无法打开所选图片" } }
                                        }, restoreCover)
                                        if(!creating) c.back()
                                    } finally { saving = false }
                                }
                            }) { Text(if(saving) "保存中…" else "保存") }
                    }
                }
            }
        }
    }
    if(choosingTags) {
        val library by c.app.keywords.state.collectAsStateWithLifecycle()
        KeywordLibrarySelectionDialog(library, tags, onDismiss = { choosingTags = false }, onConfirm = {
            tags = ArrayList(it)
            choosingTags = false
        }, validateSelection = { selection ->
            if(selection != originalTags && localSeriesTagLength(selection) > LOCAL_SERIES_LIST_FIELD_LIMIT)
                "标签总长度不能超过 $LOCAL_SERIES_LIST_FIELD_LIMIT 个字符" else null
        })
    }
    if(discard) ConfirmDialog("放弃本次修改？", "返回后将丢弃本次编辑。", { discard = false }, "放弃修改", c::back)
}

@Composable private fun LocalSeriesTagsField(
    tags: List<String>, labels: Map<String, String>, enabled: Boolean, onChoose: () -> Unit, onRemove: (String) -> Unit,
) {
    AppOutlinedButton(onChoose, Modifier.fillMaxWidth().testTag("local-series-tags"), enabled = enabled,
        shape = RoundedCornerShape(14.dp)) {
        Icon(Icons.Outlined.Label, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("从标签库选择")
    }
    if(tags.isNotEmpty()) AppChipFlowRow {
        tags.distinct().forEach { tag ->
            val label = labels[tag] ?: tag
            AppSelectionChip(true, { onRemove(tag) }, label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                enabled = enabled, modifier = Modifier.testTag("local-series-tag-$tag"), trailingIcon = {
                    Icon(Icons.Outlined.Close, "移除 $label", Modifier.size(16.dp))
                })
        }
    }
}

@Composable private fun LocalSeriesCoverField(
    preview: BookCard, useSavedCover: Boolean, hasCustomCover: Boolean, enabled: Boolean,
    onPick: () -> Unit, onRestore: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(preview, Modifier.width(70.dp).height(98.dp), useCustomCover = useSavedCover)
        Column(Modifier.weight(1f)) {
            Text("封面", style = MaterialTheme.typography.titleMedium)
            AppFilledTonalButton(onPick, enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                Icon(Icons.Outlined.Image, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if(hasCustomCover) "更换封面" else "选择封面")
            }
            if(hasCustomCover) AppTextButton(onRestore, enabled = enabled) { Text("恢复默认封面") }
        }
    }
}

@Composable private fun LocalSeriesFolderField(folders: List<Folder>, selectedId: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        AppOutlinedButton({ open = true }, Modifier.fillMaxWidth().testTag("local-series-folder"), enabled = enabled,
            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("收藏夹", style = MaterialTheme.typography.labelSmall)
                Text(folders.firstOrNull { it.id == selectedId }?.title ?: DEFAULT_FOLDER,
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        AppDropdownMenu(open, { open = false }) {
            folders.forEach { folder ->
                DropdownMenuItem({ Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    { open = false; onSelect(folder.id) },
                    trailingIcon = if(folder.id == selectedId) ({ Icon(Icons.Outlined.Check, null) }) else null)
            }
        }
    }
}

@Composable private fun LocalSeriesVolumeEmpty(message: String) {
    ProfileDetailCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun LocalSeriesVolumeItem(book: BookCard, subtitle: String?, modifier: Modifier, trailing: @Composable () -> Unit) {
    ProfileDetailCard {
        AppListItem(
            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
            supportingContent = subtitle?.let { value -> { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            leadingContent = { BookCover(book, Modifier.width(38.dp).height(54.dp), showFallbackTitle = false) },
            trailingContent = trailing,
            containerColor = Color.Transparent,
        )
    }
}
