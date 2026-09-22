@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.catalog.*
import cc.novelia.app.ui.components.*
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KeywordLibraryActions(
    val createCategory: (String) -> Unit,
    val renameCategory: (String, String) -> Unit,
    val deleteCategory: (String) -> Unit,
    val editEntry: (String, String, String) -> Unit,
)

@Composable fun rememberKeywordLibraryActions(store: KeywordStore): KeywordLibraryActions = remember(store) {
    KeywordLibraryActions(store::createCategory, store::renameCategory, store::deleteCategory, store::editEntry)
}

@Composable fun KeywordLibraryScreen(c: AppController) {
    val library by c.app.keywords.state.collectAsStateWithLifecycle()
    val error by c.app.keywords.persistenceError.collectAsStateWithLifecycle()
    KeywordLibraryContent(library.entries, library.categories, c::back, c.app.keywords::setTranslation,
        actions = rememberKeywordLibraryActions(c.app.keywords), persistenceError = error)
}

@Composable fun KeywordLibraryDialog(
    entries: List<KeywordEntry>, categories: List<String>, onDismiss: () -> Unit,
    onSaveTranslation: (String, String) -> Unit, actions: KeywordLibraryActions?,
    onInclude: (KeywordEntry) -> Unit, onExclude: (KeywordEntry) -> Unit,
    included: List<String>, excluded: List<String>, persistenceError: String?,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding()) {
            KeywordLibraryContent(entries, categories, onDismiss, onSaveTranslation, actions, onInclude, onExclude,
                included, excluded, persistenceError)
        }
    }
}

@Composable fun KeywordLibraryContent(
    entries: List<KeywordEntry>, categories: List<String>, onBack: () -> Unit,
    onSaveTranslation: (String, String) -> Unit,
    actions: KeywordLibraryActions? = null,
    onInclude: ((KeywordEntry) -> Unit)? = null, onExclude: ((KeywordEntry) -> Unit)? = null,
    included: List<String> = emptyList(), excluded: List<String> = emptyList(), persistenceError: String? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var managing by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<KeywordEntry?>(null) }
    val activeCategory = category.takeIf { it == "全部" || it in categories } ?: "全部"
    LaunchedEffect(categories) { if(category != "全部" && category !in categories) category = "全部" }
    val settledQuery = rememberDebouncedQuery(query)
    // Observe here before Scaffold subcomposes its content, including results that finish before layout.
    val results = produceState<List<KeywordEntry>?>(null, entries, settledQuery, activeCategory) {
        value = null
        value = withContext(Dispatchers.Default) { KeywordCatalog.suggestions(entries, settledQuery, activeCategory, KeywordCatalog.MAX_ENTRIES) }
    }.value
    Screen("标签库", onBack, actions = {
        if(actions != null) TextButton(onClick = { managing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("管理分类") }
    }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜索原文、译名或别名") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp).testTag("keyword-library-search"))
            KeywordCategoryChips(listOf("全部") + categories, activeCategory, { category = it }, Modifier.fillMaxWidth().testTag("keyword-library-categories"))
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when { results == null -> "正在检索…"; results!!.size == entries.size -> "${entries.size} 个标签";
                        else -> "${results!!.size} / ${entries.size} 个标签" }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if(included.isNotEmpty() || excluded.isNotEmpty()) Text("包含 ${included.size} · 排除 ${excluded.size}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(if(onInclude != null) "点按标签选择包含或排除，返回后应用" else "点按标签编辑译名与分类",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            if(results?.isEmpty() == true) EmptyState("没有匹配的标签", "换个关键词或分类试试。", Icons.Outlined.SearchOff)
            else key(settledQuery, activeCategory) {
                KeywordTagCloud(results.orEmpty(), included, excluded, { editing = it }, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    editing?.let { selected ->
        val latest = entries.firstOrNull { it.original == selected.original } ?: selected
        KeywordEditorDialog(latest, { editing = null }, onSaveTranslation,
            onInclude = onInclude?.let { include -> { include(latest); editing = null } },
            onExclude = onExclude?.let { exclude -> { exclude(latest); editing = null } },
            categories = categories, onSaveDetails = actions?.editEntry)
    }
    if(managing && actions != null) KeywordCategoriesSheet(entries, categories, actions) { managing = false }
}

@Composable private fun KeywordCategoriesSheet(entries: List<KeywordEntry>, categories: List<String>, actions: KeywordLibraryActions, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val counts = remember(entries) { entries.groupingBy { it.category }.eachCount() }
    AppSheet(onDismissRequest = onDismiss) {
        Text("分类管理", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Button(onClick = { creating = true }, enabled = categories.size < KeywordLibrary.MAX_CATEGORIES,
            modifier = Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp)) { Icon(Icons.Outlined.Add, null); Text("新建分类") }
        error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        AppLazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(categories, key = { it }) { name ->
                ListItem(headlineContent = { Text(name) }, supportingContent = { Text("${counts[name] ?: 0} 个标签") },
                    trailingContent = {
                        if(name == KeywordLibrary.OTHER) Text("默认归类", style = MaterialTheme.typography.labelSmall)
                        else Row {
                            IconButton(onClick = { renaming = name }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "重命名分类 $name") }
                            IconButton(onClick = { deleting = name }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.DeleteOutline, "删除分类 $name") }
                        }
                    })
            }
        }
    }
    if(creating || renaming != null) CategoryNameDialog(renaming, categories, { creating = false; renaming = null }) { name ->
        val old = renaming
        if(old == null) actions.createCategory(name) else actions.renameCategory(old, name)
    }
    deleting?.let { name -> ConfirmDialog("删除分类“$name”？", "分类中的 ${counts[name] ?: 0} 个标签会移到“其他”，标签原文和译名都会保留。", { deleting = null }, "删除分类") {
        runCatching { actions.deleteCategory(name) }.onFailure { error = it.message }
    } }
}

@Composable private fun CategoryNameDialog(initial: String?, categories: List<String>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val normalized = name.trim()
    val invalid = runCatching { KeywordLibrary.requireCategoryName(normalized) }.exceptionOrNull()?.message
        ?: if(normalized != initial && normalized in categories) "分类名称已存在" else null
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(if(initial == null) "新建分类" else "重命名分类") },
        text = { OutlinedTextField(name, { name = it; error = null }, label = { Text("分类名称") }, singleLine = true,
            isError = (name.isNotEmpty() && invalid != null) || error != null,
            supportingText = { Text(error ?: invalid?.takeIf { name.isNotEmpty() } ?: "最多 ${KeywordLibrary.MAX_CATEGORY_LENGTH} 个字符") },
            modifier = Modifier.testTag("keyword-category-name")) },
        confirmButton = { TextButton(onClick = { try { onSave(normalized); onDismiss() } catch(failure: IllegalArgumentException) { error = failure.message } },
            enabled = invalid == null && normalized != initial) { Text(if(initial == null) "创建分类" else "保存名称") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
