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
import cc.novelia.app.ui.theme.motionClickable
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
    val settledQuery = rememberDebouncedQuery(query)
    val results by produceState<List<KeywordEntry>?>(null, entries, settledQuery, activeCategory) {
        value = null
        value = withContext(Dispatchers.Default) { KeywordCatalog.suggestions(entries, settledQuery, activeCategory, KeywordCatalog.MAX_ENTRIES) }
    }
    Screen("标签库", onBack, actions = {
        if(actions != null) TextButton(onClick = { managing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("管理分类") }
    }) { padding ->
        Column(Modifier.padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索原文、中文译名或别名") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().testTag("keyword-library-search"))
                KeywordCategoryPicker(listOf("全部") + categories, activeCategory, { category = it })
                Text(if(results == null) "正在检索…" else "${results!!.size} 个标签 · 标签库共 ${entries.size} 个", style = MaterialTheme.typography.labelLarge)
                if(onInclude != null) Text("点击标签选择包含或排除，返回后统一应用搜索。已选包含 ${included.size} 个、排除 ${excluded.size} 个。", style = MaterialTheme.typography.bodySmall)
                else Text("收录常用标签和浏览作品时遇到的标签，分类和译名保存在本机。", style = MaterialTheme.typography.bodySmall)
                persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()
            AppLazyColumn(Modifier.weight(1f).testTag("keyword-library-list"), contentPadding = PaddingValues(bottom = 20.dp)) {
                if(results?.isEmpty() == true) item { EmptyState("没有匹配的标签", "换个关键词或分类试试。", Icons.Outlined.SearchOff) }
                items(results.orEmpty(), key = { it.original }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.original) },
                        supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if(entry.translation.isNotBlank()) Text(entry.translation)
                            Text(entry.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        } },
                        trailingContent = {
                            when(entry.original) {
                                in included -> Text("已包含", color = MaterialTheme.colorScheme.primary)
                                in excluded -> Text("已排除", color = MaterialTheme.colorScheme.error)
                                else -> Icon(Icons.Outlined.Edit, "编辑标签")
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("library-tag-${entry.original}").motionClickable { editing = entry })
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
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
