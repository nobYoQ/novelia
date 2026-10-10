package cc.novelia.app.ui.components

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppOutlinedButton

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class QuickFilter(val label: String, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit)

/** 按优先级显示当前屏幕能容纳的快捷筛选项。 */
@Composable internal fun QuickFilterBar(filters: List<QuickFilter>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val count = when {
            maxWidth >= 300.dp -> 3
            maxWidth >= 200.dp -> 2
            else -> 1
        }
        Row {
            filters.take(count).forEach { filter ->
                QuickFilterButton(filter, Modifier.weight(1f).padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable internal fun QuickFilterButton(filter: QuickFilter, modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(modifier) {
        var expanded by remember { mutableStateOf(false) }
        val selectedOption = filter.options.getOrElse(filter.selected) { filter.options.first() }
        val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = "${filter.label}：$selectedOption" }
        val content: @Composable RowScope.() -> Unit = {
            Text(selectedOption, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if(compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp))
        }
        if(compact) AppTextButton(onClick = { expanded = true }, modifier = buttonModifier,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp), content = content)
        else AppOutlinedButton(onClick = { expanded = true }, modifier = buttonModifier,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), content = content)
        AppDropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 144.dp)) {
            filter.options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; filter.onSelect(index) })
            }
        }
    }
}
