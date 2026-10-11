@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.community.ForumCategory

@Composable internal fun ForumCategoryTabs(categories: List<ForumCategory>, selected: String, onSelect: (String) -> Unit) {
    val index = categories.indexOfFirst { it.slug == selected }.coerceAtLeast(0)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("forum-category-tabs")) {
        val equalWidths = 96.dp * categories.size * fontScale <= maxWidth
        // 滚动模式也至少铺满可用宽度，避免临界字号下再次留下尾部空白。
        val minimumWidth = if(equalWidths) 0.dp else ((maxWidth - 24.dp) / categories.size).coerceAtLeast(0.dp)
        val tabs: @Composable () -> Unit = {
            categories.forEach { category -> Tab(category.slug == selected, { onSelect(category.slug) },
                modifier = Modifier.widthIn(min = minimumWidth).testTag("forum-category-${category.slug}"), text = { Text(category.title) }) }
        }
        if(equalWidths) PrimaryTabRow(index, tabs = tabs)
        else PrimaryScrollableTabRow(index, edgePadding = 12.dp, tabs = tabs)
    }
}

/** 收起只隐藏输入框，保留查询；筛选状态通过图标颜色与无障碍描述告知。 */
@Composable internal fun ForumFeedControls(title: String, search: String, expanded: Boolean,
    searchLabel: String, onExpanded: (Boolean) -> Unit, onSearch: (String) -> Unit,
    actions: @Composable RowScope.() -> Unit) {
    val focusManager = LocalFocusManager.current
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            actions()
            AppIconButton(onClick = { if(expanded) focusManager.clearFocus(); onExpanded(!expanded) },
                modifier = Modifier.testTag("forum-search-toggle").semantics {
                    stateDescription = if(search.isBlank()) "未筛选" else "搜索：$search"
                }) {
                Icon(if(expanded) Icons.Outlined.Close else Icons.Outlined.Search,
                    if(expanded) "收起搜索" else "展开搜索",
                    tint = if(search.isNotBlank()) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
        }
        if(expanded) OutlinedTextField(search, onSearch, label = { Text(searchLabel) }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if(search.isNotEmpty()) AppIconButton(onClick = { onSearch("") }) { Icon(Icons.Outlined.Close, "清空搜索") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("forum-search-input"),
            shape = MaterialTheme.shapes.extraLarge)
    }
}
