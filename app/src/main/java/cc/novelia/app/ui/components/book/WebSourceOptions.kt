package cc.novelia.app.ui.components.book

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.ui.components.base.AppChipFlowRow
import cc.novelia.app.ui.components.base.AppSelectionChip
import cc.novelia.app.ui.components.base.AppTextButton

/** 发现和云端收藏共用书源标签；空选择的接口含义由各入口处理。 */
@Composable internal fun WebSourceOptions(
    selected: Set<String>, onChange: (Set<String>) -> Unit, modifier: Modifier = Modifier,
    allowEmptySelection: Boolean = false, tagPrefix: String = "web-source",
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if(allowEmptySelection) "书源（可多选）" else "书源（可多选，至少保留一个）",
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            AppTextButton(onClick = { onChange(providers.keys.toSet()) }, enabled = selected != providers.keys) { Text("全选") }
        }
        AppChipFlowRow(Modifier.padding(vertical = 10.dp)) {
            providers.forEach { (id, title) ->
                AppSelectionChip(id in selected, onClick = {
                    val next = selected.toMutableSet().apply { if(!add(id)) remove(id) }
                    if(allowEmptySelection || next.isNotEmpty()) onChange(next)
                }, label = { Text(title) }, modifier = Modifier.testTag("$tagPrefix-$id"))
            }
        }
    }
}
