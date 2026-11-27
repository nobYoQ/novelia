@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.discover

import cc.novelia.app.ui.components.base.AppTextField
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppChipFlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.SearchExpression
import cc.novelia.app.data.catalog.KeywordSelection
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppPageTabs
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.FilterPanelExpandIcon
import cc.novelia.app.ui.components.base.FilterPanelVisibility
import cc.novelia.app.ui.components.keywords.KeywordCategoryChips
import cc.novelia.app.ui.components.keywords.KeywordSelectionChip
import cc.novelia.app.ui.components.base.rememberPanelScrollState
import cc.novelia.app.ui.theme.appReducedMotion

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
    var replacing by remember { mutableStateOf(false) }
    var browsingLibrary by rememberSaveable { mutableStateOf(false) }
    var keywordTab by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(categoryNames) { if(category != "全部" && category !in categoryNames) category = "全部" }
    // 保留尚未应用的搜索条件，每次展开则从面板顶部开始。
    val panelScroll = key(keywordTab) { rememberPanelScrollState(expanded) }
    val focus = LocalFocusManager.current
    val generated = SearchExpression.build(all, any, exact, excluded, includedTags.joinToString(" "), excludedTags.joinToString(" "), minimum, maximum)
    val invalidBounds = minimum.toIntOrNull()?.let { min -> maximum.toIntOrNull()?.let { max -> min >= max } } == true
    val conflicts = SearchExpression.conflictingTags(query, generated)
    val lookup = remember(entries) { entries.associateBy { it.original } }
    val candidates = remember(entries, tagQuery, category) { KeywordCatalog.suggestions(entries, tagQuery, category, 12) }
    val manual = tagQuery.trim().takeIf { it.isNotBlank() && KeywordCatalog.exactMatch(entries, it) == null }
    val selection = KeywordSelection(includedTags, excludedTags)
    fun updateSelection(next: KeywordSelection) {
        includedTags = next.included
        excludedTags = next.excluded
    }
    fun selectTags(originals: List<String>, include: Boolean) = updateSelection(KeywordSelection(includedTags, excludedTags).add(originals, include))
    fun cycleTag(original: String) = updateSelection(KeywordSelection(includedTags, excludedTags).cycle(original))
    fun removeTag(original: String) = updateSelection(KeywordSelection(includedTags, excludedTags).remove(original))
    fun apply(expression: String) {
        onApply(expression)
        all = ""; any = ""; exact = ""; excluded = ""; minimum = ""; maximum = ""
        includedTags = emptyList(); excludedTags = emptyList(); tagQuery = ""
        keywordTab = false
        onExpandedChange(false)
    }
    val searchActions: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if(query.isNotBlank()) AppTextButton(onClick = { replacing = true }, enabled = generated.isNotBlank() && !invalidBounds) {
                Text("替换条件…")
            }
            AppButton(onClick = { apply(SearchExpression.append(query, generated)) },
                enabled = generated.isNotBlank() && !invalidBounds && conflicts.isEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("assistant-append")) {
                Text(if(query.isBlank()) "应用并搜索" else "追加并搜索")
            }
        }
    }
    Surface(modifier.fillMaxWidth(), color = if(sheetMode) Color.Transparent else MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column {
            if(sheetMode) Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("辅助搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                AppTextButton(onClick = onHelp) { Text("搜索语法") }
                if(!appReducedMotion()) AppIconButton(onClick = { onExpandedChange(false) }) { Icon(Icons.Outlined.Close, "关闭辅助搜索") }
            } else AppTextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.fillMaxWidth().testTag("search-assistant-toggle")) {
                Text("辅助搜索", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if(generated.isNotBlank() && !expanded) Text("有待应用条件", style = MaterialTheme.typography.labelSmall)
                FilterPanelExpandIcon(expanded, if(expanded) "收起辅助搜索" else "展开辅助搜索")
            }
            persistenceError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
            FilterPanelVisibility(expanded) {
                val panelHeight = (LocalConfiguration.current.screenHeightDp * (if(sheetMode) .72f else .43f))
                    .coerceIn(140f, if(sheetMode) 720f else 400f).dp
                BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = panelHeight)) {
                    val compactPreview = WindowInsets.isImeVisible || maxHeight < 320.dp
                    val fixedActions = sheetMode && !compactPreview && LocalDensity.current.fontScale <= 1.5f
                    Column {
                        SearchAssistantPreview(query, generated, compact = compactPreview,
                            hasError = invalidBounds || conflicts.isNotEmpty())
                        if(sheetMode) AppPageTabs(if(keywordTab) 1 else 0, 2) { index ->
                            Tab(selected = keywordTab == (index == 1), onClick = { focus.clearFocus(); keywordTab = index == 1 },
                                text = { Text(if(index == 0) "标签" else "关键词与章节") })
                        }
                        AppScrollColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("search-assistant-content"),
                            state = panelScroll,
                            contentModifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if(conflicts.isNotEmpty()) Text("${conflicts.joinToString("、")} 的包含与排除冲突，请调整或替换条件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            if(!sheetMode || !keywordTab) {
                                AppTextField(tagQuery, { tagQuery = it }, label = { Text("原文或中文标签") }, singleLine = true,
                                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    trailingIcon = { AppTextButton(onClick = { browsingLibrary = true }, modifier = Modifier.testTag("assistant-open-library")
                                        .semantics { contentDescription = "浏览全部 ${entries.size} 个标签" }) {
                                        Text("标签库")
                                    } },
                                    isError = tagQuery.trim().length > KeywordCatalog.MAX_TEXT_LENGTH,
                                    supportingText = if(tagQuery.trim().length > KeywordCatalog.MAX_TEXT_LENGTH) ({ Text("最多 ${KeywordCatalog.MAX_TEXT_LENGTH} 个字符") }) else null,
                                    modifier = Modifier.fillMaxWidth().testTag("assistant-tag-input"))
                                KeywordCategoryChips(listOf("全部") + categoryNames, category, { category = it }, Modifier.fillMaxWidth(), PaddingValues(vertical = 4.dp))
                                if(candidates.isNotEmpty() || manual != null) Text("点一次包含，再点排除", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AppChipFlowRow() {
                                    candidates.forEach { entry ->
                                        key(entry.original) {
                                            KeywordSelectionChip(entry.label, selection.stateOf(entry.original), { cycleTag(entry.original) },
                                                modifier = Modifier.testTag("assistant-candidate-${entry.original}"), enabled = KeywordCatalog.canSearch(entry.original))
                                        }
                                    }
                                }
                                if(manual != null) {
                                    key(manual) {
                                        KeywordSelectionChip("添加原文：$manual", selection.stateOf(manual), { cycleTag(manual) },
                                            enabled = KeywordCatalog.canSearch(manual), modifier = Modifier.testTag("assistant-manual-tag"))
                                    }
                                    if(manual.length <= KeywordCatalog.MAX_TEXT_LENGTH && !KeywordCatalog.canSearch(manual)) Text("标签不能含空格或以 - 开头，可改用关键词搜索。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                                if(candidates.isEmpty() && manual == null) Text("没有匹配的标签", style = MaterialTheme.typography.bodySmall)
                                if(includedTags.isNotEmpty() || excludedTags.isNotEmpty()) {
                                    Text("已选标签", style = MaterialTheme.typography.titleSmall)
                                    AppChipFlowRow() {
                                        (includedTags.map { it to true } + excludedTags.map { it to false }).forEach { (original, include) ->
                                            val entry = lookup[original] ?: KeywordEntry(original)
                                            key(original) {
                                                KeywordSelectionChip("${if(include) "包含" else "排除"}：${entry.label}", selection.stateOf(original), { cycleTag(original) },
                                                    modifier = Modifier.testTag("assistant-selected-$original"),
                                                    trailingIcon = { AppIconButton(onClick = { removeTag(original) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "移除条件 $original", Modifier.size(16.dp)) } })
                                            }
                                        }
                                    }
                                }
                            }
                            if(!sheetMode) HorizontalDivider()
                            if(!sheetMode || keywordTab) {
                                Text("书名、作者及关键词", style = MaterialTheme.typography.titleMedium)
                                val wordHint: @Composable () -> Unit = { Text("多个词用空格分隔") }
                                AppTextField(all, { all = it }, label = { Text("全部包含") }, placeholder = wordHint, modifier = Modifier.fillMaxWidth().testTag("assistant-all"))
                                AppTextField(any, { any = it }, label = { Text("任意包含") }, placeholder = wordHint, modifier = Modifier.fillMaxWidth())
                                AppTextField(exact, { exact = it }, label = { Text("精确短语") }, modifier = Modifier.fillMaxWidth())
                                AppTextField(excluded, { excluded = it }, label = { Text("排除关键词") }, placeholder = wordHint, modifier = Modifier.fillMaxWidth())
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if(maxWidth >= (240 * LocalDensity.current.fontScale).dp) 2 else 1) {
                                        AppTextField(minimum, { minimum = it.filter(Char::isDigit).take(6) }, label = { Text("章节数大于") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                                        AppTextField(maximum, { maximum = it.filter(Char::isDigit).take(6) }, label = { Text("章节数小于") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), isError = invalidBounds)
                                    }
                                }
                            }
                            if(invalidBounds) Text("章节数上限须大于下限", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if(!sheetMode) AppTextButton(onClick = onHelp) { Text("搜索语法") }
                            if(!fixedActions) searchActions()
                        }
                        if(fixedActions) {
                            HorizontalDivider()
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { searchActions() }
                        }
                    }
                }
            }
        }
    }
    if(browsingLibrary) KeywordLibraryDialog(entries, categoryNames, { browsingLibrary = false }, onSaveTranslation, libraryActions,
        onToggle = { entry -> cycleTag(entry.original) },
        included = includedTags, excluded = excludedTags, persistenceError = persistenceError,
        onIncludeCategory = { selectTags(it, true) }, onExcludeCategory = { selectTags(it, false) })
    if(replacing) AppAlertDialog(onDismissRequest = { replacing = false }, title = { Text("替换手工搜索条件？") },
        text = { Text("当前搜索框：\n$query\n\n替换为：\n$generated") },
        confirmButton = { AppTextButton(onClick = { replacing = false; apply(generated) }) { Text("替换并搜索") } },
        dismissButton = { AppTextButton(onClick = { replacing = false }) { Text("保留原内容") } })
}
