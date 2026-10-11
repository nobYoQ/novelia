package cc.novelia.app.ui.discover

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.ui.components.base.AppChipFlowRow
import cc.novelia.app.ui.components.base.AppSelectionChip

@Composable internal fun WebSourceFilter(source: String, onChange: (String) -> Unit) {
    // 空条件在网络接口中代表全部书源，默认、重置和旧搜索方案均显示全选。
    val selected = remember(source) { if(source.isBlank()) providers.keys.toSet() else source.split(',').toSet() }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("书源（可多选，至少保留一个）", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        AppTextButton(onClick = { onChange("") }, enabled = selected != providers.keys) { Text("全选") }
    }
    AppChipFlowRow(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        providers.forEach { (id, title) ->
            AppSelectionChip(id in selected, onClick = {
                val next = selected.toMutableSet().apply { if(!add(id)) remove(id) }
                if(next.isNotEmpty()) onChange(if(next == providers.keys) "" else providers.keys.filter { it in next }.joinToString(","))
            }, label = { Text(title) }, modifier = Modifier.testTag("web-source-$id"))
        }
    }
}
