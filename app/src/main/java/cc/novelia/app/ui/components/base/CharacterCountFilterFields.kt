package cc.novelia.app.ui.components.base

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.data.catalog.NovelLocalFilter
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString

internal val NovelLocalFilterSaver = Saver<NovelLocalFilter, String>(
    save = { appJson.encodeToString(it) }, restore = { appJson.decodeFromString<NovelLocalFilter>(it).normalized() },
)
internal val CharacterCountFilterSaver = Saver<CharacterCountFilter, String>(
    save = { appJson.encodeToString(it) }, restore = { appJson.decodeFromString<CharacterCountFilter>(it).normalized() },
)

@Composable internal fun CharacterCountFilterFields(value: CharacterCountFilter, onChange: (CharacterCountFilter) -> Unit) {
    var minimum by rememberSaveable(value.minimum, value.maximum) { mutableStateOf(value.minimum?.toString().orEmpty()) }
    var maximum by rememberSaveable(value.minimum, value.maximum) { mutableStateOf(value.maximum?.toString().orEmpty()) }
    val min = minimum.toLongOrNull()
    val max = maximum.toLongOrNull()
    val invalid = (minimum.isNotBlank() && min == null) || (maximum.isNotBlank() && max == null) || (min != null && max != null && min > max)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("字数范围", style = MaterialTheme.typography.titleSmall)
        val presets = listOf("不限" to CharacterCountFilter(), "10 万以下" to CharacterCountFilter(maximum = 99_999),
            "10～50 万" to CharacterCountFilter(100_000, 499_999), "50 万及以上" to CharacterCountFilter(minimum = 500_000))
        AppChipFlowRow {
            presets.forEach { (label, filter) ->
                AppSelectionChip(value.minimum == filter.minimum && value.maximum == filter.maximum,
                    onClick = { onChange(filter.copy(includeUnknown = value.includeUnknown)) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(minimum, { minimum = it.filter { char -> char in '0'..'9' }.take(12) },
                label = { Text("最少字数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("characters-minimum"))
            OutlinedTextField(maximum, { maximum = it.filter { char -> char in '0'..'9' }.take(12) },
                label = { Text("最多字数") }, singleLine = true, isError = invalid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("characters-maximum"))
        }
        Text(if(invalid) "最多字数不能小于最少字数。" else "单位为字，包含上下限；留空表示不限。", style = MaterialTheme.typography.bodySmall,
            color = if(invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        AppTextButton(onClick = { onChange(CharacterCountFilter(min, max, value.includeUnknown)) }, enabled = !invalid,
            modifier = Modifier.testTag("apply-character-filter")) { Text("应用字数范围") }
        TogglePreference("包含字数未知作品", "未提供字数或暂时获取失败的作品也会保留", value.includeUnknown) {
            onChange(value.copy(includeUnknown = it))
        }
    }
}
