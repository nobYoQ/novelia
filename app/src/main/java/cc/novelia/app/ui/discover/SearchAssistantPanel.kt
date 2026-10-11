@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.discover

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppSelectionChip
import cc.novelia.app.ui.components.base.AppActionChip
import cc.novelia.app.ui.components.base.AppChipFlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.SearchExpression
import cc.novelia.app.data.catalog.KeywordSelection
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.FilterPanelExpandIcon
import cc.novelia.app.ui.components.base.FilterPanelVisibility
import cc.novelia.app.ui.components.keywords.KeywordEditorDialog
import cc.novelia.app.ui.components.keywords.KeywordCategoryChips
import cc.novelia.app.ui.components.base.rememberPanelScrollState

@Composable
fun SearchAssistantPanel(
    query: String,
    entries: List<KeywordEntry>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onApply: (String) -> Unit,
    onSaveTranslation: (String, String) -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    persistenceError: String? = null,
    categoryNames: List<String> = KeywordCatalog.defaultCategories,
    libraryActions: KeywordLibraryActions? = null,
    sheetMode: Boolean = false,
) {
    var all by rememberSaveable { mutableStateOf("") }
    var any by rememberSaveable { mutableStateOf("") }
    var exact by rememberSaveable { mutableStateOf("") }
    var excluded by rememberSaveable { mutableStateOf("") }
    var minimum by rememberSaveable { mutableStateOf("") }
    var maximum by rememberSaveable { mutableStateOf("") }
    var includedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var excludedTags by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var tagQuery by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部") }
    var editing by remember { mutableStateOf<KeywordEntry?>(null) }
    var replacing by remember { mutableStateOf(false) }
    var browsingLibrary by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(categoryNames) { if(category != "全部" && category !in categoryNames) category = "全部" }
    // 保留尚未应用的搜索条件，每次展开则从面板顶部开始。
    val panelScroll = rememberPanelScrollState(expanded)
    val generated = SearchExpression.build(all, any, exact, excluded, includedTags.joinToString(" "), excludedTags.joinToString(" "), minimum, maximum)
    val invalidBounds = minimum.toIntOrNull()?.let { min -> maximum.toIntOrNull()?.let { max -> min >= max } } == true
    val conflicts = SearchExpression.conflictingTags(query, generated)
    val lookup = remember(entries) { entries.associateBy { it.original } }
    val candidates = remember(entries, tagQuery, category) { KeywordCatalog.suggestions(entries, tagQuery, category, 12) }
    val manual = tagQuery.trim().takeIf { it.isNotBlank() && KeywordCatalog.exactMatch(entries, it) == null }
    fun selectTags(originals: List<String>, include: Boolean) {
        val next = KeywordSelection(includedTags, excludedTags).add(originals, include)
        includedTags = next.included
        excludedTags = next.excluded
    }
    fun removeTag(original: String) { includedTags = includedTags - original; excludedTags = excludedTags - original }
    fun apply(expression: String) {
        onApply(expression)
        all = ""; any = ""; exact = ""; excluded = ""; minimum = ""; maximum = ""
        includedTags = emptyList(); excludedTags = emptyList(); tagQuery = ""
        onExpandedChange(false)
    }
    Surface(modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column {
            if(sheetMode) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("辅助搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                AppTextButton(onClick = { onExpandedChange(false) }) { Text("关闭") }
            } else AppTextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.fillMaxWidth().testTag("search-assistant-toggle")) {
                Text("辅助搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if(generated.isNotBlank() && !expanded) Text("有待应用条件", style = MaterialTheme.typography.labelSmall)
                FilterPanelExpandIcon(expanded, if(expanded) "收起辅助搜索" else "展开辅助搜索")
            }
            persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            FilterPanelVisibility(expanded) {
                val panelHeight = (LocalConfiguration.current.screenHeightDp * (if(sheetMode) .72f else .43f))
                    .coerceIn(140f, if(sheetMode) 720f else 400f).dp
                AppScrollColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = panelHeight).testTag("search-assistant-content"),
                    state = panelScroll,
                    contentModifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("选好条件后再应用，搜索框中的手工表达式会保留。", style = MaterialTheme.typography.bodySmall)
                    Text("标签检索", style = MaterialTheme.typography.titleSmall)
                    AppOutlinedButton(onClick = { browsingLibrary = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assistant-open-library")) {
                        Text("浏览全部标签（${entries.size}）")
                    }
                    OutlinedTextField(tagQuery, { tagQuery = it }, label = { Text("输入原文或中文标签") }, singleLine = true,
                        isError = tagQuery.trim().length > KeywordCatalog.MAX_TEXT_LENGTH,
                        supportingText = { if(tagQuery.trim().length > KeywordCatalog.MAX_TEXT_LENGTH) Text("标签最多 ${KeywordCatalog.MAX_TEXT_LENGTH} 字符，请缩短后添加。") },
                        modifier = Modifier.fillMaxWidth().testTag("assistant-tag-input"))
                    KeywordCategoryChips(listOf("全部") + categoryNames, category, { category = it }, Modifier.fillMaxWidth(), PaddingValues(vertical = 4.dp))
                    Text("这里显示最多 12 个候选。标签库中可按分类批量包含或排除，返回后逐项调整。", style = MaterialTheme.typography.bodySmall)
                    AppChipFlowRow() {
                        candidates.forEach { entry ->
                            AppActionChip(onClick = { editing = entry }, label = { Text(entry.label) },
                                modifier = Modifier.testTag("assistant-candidate-${entry.original}"))
                        }
                    }
                    if(manual != null) {
                        AppOutlinedButton(onClick = { editing = KeywordEntry(manual) }, enabled = KeywordCatalog.canSearch(manual), modifier = Modifier.testTag("assistant-manual-tag")) {
                            Text("添加原文：$manual")
                        }
                        if(manual.length <= KeywordCatalog.MAX_TEXT_LENGTH && !KeywordCatalog.canSearch(manual)) Text("原站标签不能包含空白或以 - 开头。可在下方用普通关键词搜索。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if(candidates.isEmpty() && manual == null) Text("这个分类暂时没有匹配标签。可以切换分类或输入标签原文。", style = MaterialTheme.typography.bodySmall)
                    if(includedTags.isNotEmpty() || excludedTags.isNotEmpty()) {
                        Text("已选条件", style = MaterialTheme.typography.titleSmall)
                        Text("包含标签需全部满足；命中任一排除标签即排除。点按可修改，× 可移除。", style = MaterialTheme.typography.bodySmall)
                        AppChipFlowRow() {
                            (includedTags.map { it to true } + excludedTags.map { it to false }).forEach { (original, include) ->
                                val entry = lookup[original] ?: KeywordEntry(original)
                                AppSelectionChip(true, onClick = { editing = entry }, label = { Text("${if(include) "包含" else "排除"}：${entry.label}") },
                                    trailingIcon = { AppIconButton(onClick = { removeTag(original) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "移除条件 $original", Modifier.size(16.dp)) } })
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("书名、作者及关键词", style = MaterialTheme.typography.titleSmall)
                    Text("多个词用空格分开；精确短语会作为一个完整条件。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(all, { all = it }, label = { Text("全部包含") }, modifier = Modifier.fillMaxWidth().testTag("assistant-all"))
                    OutlinedTextField(any, { any = it }, label = { Text("任意包含") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(exact, { exact = it }, label = { Text("精确短语") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(excluded, { excluded = it }, label = { Text("排除关键词") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(minimum, { minimum = it.filter(Char::isDigit).take(6) }, label = { Text("章节数大于") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        OutlinedTextField(maximum, { maximum = it.filter(Char::isDigit).take(6) }, label = { Text("章节数小于") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), isError = invalidBounds)
                    }
                    if(invalidBounds) Text("上限需要大于下限；章节数不包含填写的边界值。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("生成的条件", style = MaterialTheme.typography.labelLarge)
                    Text(generated.ifBlank { "填写后在这里预览" }, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("assistant-preview"))
                    if(query.isNotBlank()) {
                        Text("追加后的搜索", style = MaterialTheme.typography.labelLarge)
                        Text(SearchExpression.append(query, generated), style = MaterialTheme.typography.bodySmall)
                    }
                    if(conflicts.isNotEmpty()) Text("这些标签与搜索框中的包含／排除条件相反：${conflicts.joinToString("、")}。请调整原条件，或选择替换搜索框。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    AppButton(onClick = { apply(SearchExpression.append(query, generated)) }, enabled = generated.isNotBlank() && !invalidBounds && conflicts.isEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("assistant-append")) { Text(if(query.isBlank()) "应用并搜索" else "追加条件并搜索") }
                    if(query.isNotBlank()) AppTextButton(onClick = { replacing = true }, enabled = generated.isNotBlank() && !invalidBounds, modifier = Modifier.fillMaxWidth()) { Text("用这些条件替换搜索框…") }
                    AppTextButton(onClick = onHelp) { Text("查看原站搜索语法") }
                }
            }
        }
    }
    editing?.let { entry ->
        KeywordEditorDialog(entry, onDismiss = { editing = null }, onSave = onSaveTranslation,
            onInclude = { selectTags(listOf(entry.original), true); editing = null },
            onExclude = { selectTags(listOf(entry.original), false); editing = null },
            categories = categoryNames, onSaveDetails = libraryActions?.editEntry,
            onRemove = if(entry.original in includedTags || entry.original in excludedTags) ({ removeTag(entry.original); editing = null }) else null)
    }
    if(browsingLibrary) KeywordLibraryDialog(entries, categoryNames, { browsingLibrary = false }, onSaveTranslation, libraryActions,
        onInclude = { entry -> selectTags(listOf(entry.original), true) },
        onExclude = { entry -> selectTags(listOf(entry.original), false) },
        included = includedTags, excluded = excludedTags, persistenceError = persistenceError,
        onIncludeCategory = { selectTags(it, true) }, onExcludeCategory = { selectTags(it, false) }, onRemove = { removeTag(it.original) })
    if(replacing) AppAlertDialog(onDismissRequest = { replacing = false }, title = { Text("替换手工搜索条件？") },
        text = { Text("当前搜索框：\n$query\n\n替换为：\n$generated") },
        confirmButton = { AppTextButton(onClick = { replacing = false; apply(generated) }) { Text("替换并搜索") } },
        dismissButton = { AppTextButton(onClick = { replacing = false }) { Text("保留原内容") } })
}
