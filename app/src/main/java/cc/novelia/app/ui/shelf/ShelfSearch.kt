package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.AppIconButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.FilterPanelVisibility

@Composable internal fun ShelfSearchToggle(
    expanded: Boolean, active: Boolean, onToggle: () -> Unit, tagPrefix: String, enabled: Boolean = true,
) {
    AppIconButton(onClick = onToggle, enabled = enabled,
        modifier = Modifier.size(48.dp).testTag("$tagPrefix-search-toggle").semantics {
            stateDescription = if(expanded) "搜索已展开" else if(active) "搜索已收起，关键词仍生效" else "搜索已收起"
        }, colors = IconButtonDefaults.iconButtonColors(
            contentColor = if(expanded || active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(Icons.Outlined.Search, if(expanded) "收起书架搜索" else "展开书架搜索")
    }
}

/** 搜索位于筛选区下方，展开状态与筛选、批量整理各自独立。 */
@Composable internal fun ShelfSearchField(
    expanded: Boolean, query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit, onClear: () -> Unit,
    label: String, tagPrefix: String, enabled: Boolean = true,
) {
    val focus = LocalFocusManager.current
    val submit = { focus.clearFocus(); onSubmit() }
    FilterPanelVisibility(expanded) {
        OutlinedTextField(query, onQuery, enabled = enabled, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            trailingIcon = {
                Row {
                    if(query.isNotEmpty()) AppIconButton(onClick = onClear, enabled = enabled,
                        modifier = Modifier.testTag("$tagPrefix-search-clear")) { Icon(Icons.Outlined.Close, "清空搜索") }
                    AppIconButton(onClick = submit, enabled = enabled, modifier = Modifier.testTag("$tagPrefix-search-submit")) {
                        Icon(Icons.Outlined.Search, "搜索书架")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("$tagPrefix-search-field"))
    }
}
