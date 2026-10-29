package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderStatusItems
import cc.novelia.app.ui.components.base.*

@Composable internal fun ReaderStatusBarPreferences(items: List<String>, defaults: List<String>, onChange: (List<String>) -> Unit) {
    Text("勾选显示内容，用箭头调整顺序。页码仅在分页阅读时显示；窄屏和大字体会自动分行。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
    val all = items + ReaderStatusItems.labels.keys.filterNot { it in items }
    all.forEach { item ->
        val selected = item in items
        val index = items.indexOf(item)
        val label = ReaderStatusItems.labels.getValue(item)
        fun move(step: Int) {
            val target = index + step
            if(index in items.indices && target in items.indices) onChange(items.toMutableList().apply { removeAt(index); add(target, item) })
        }
        ListItem(modifier = Modifier.testTag("reader-status-option-$item").toggleable(selected, role = Role.Checkbox,
            onValueChange = { onChange(if(it) items + item else items - item) }),
            leadingContent = { AppCheckbox(checked = selected, onCheckedChange = null) }, headlineContent = { Text(label) },
            trailingContent = { if(selected) Row {
                AppIconButton(onClick = { move(-1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "$label 前移") }
                AppIconButton(onClick = { move(1) }, enabled = index < items.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "$label 后移") }
            } })
    }
    if(items.isEmpty()) Text("未选择内容，底栏将隐藏。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
    AppTextButton(onClick = { onChange(defaults) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("恢复默认底栏内容") }
}
