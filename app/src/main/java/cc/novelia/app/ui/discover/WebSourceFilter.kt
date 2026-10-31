package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.ui.components.book.WebSourceOptions

@Composable internal fun WebSourceFilter(source: String, onChange: (String) -> Unit) {
    // 空条件在网络接口中代表全部书源，默认、重置和旧搜索方案均显示全选。
    val selected = remember(source) { if(source.isBlank()) providers.keys.toSet() else source.split(',').toSet() }
    WebSourceOptions(selected, onChange = { next ->
        onChange(if(next == providers.keys) "" else providers.keys.filter { it in next }.joinToString(","))
    }, modifier = Modifier.padding(horizontal = 20.dp))
}
