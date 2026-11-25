package cc.novelia.app.ui.components.base

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    val presets = listOf("不限" to CharacterCountFilter(), "10 万以下" to CharacterCountFilter(maximum = 99_999),
        "10～50 万" to CharacterCountFilter(100_000, 499_999), "50 万及以上" to CharacterCountFilter(minimum = 500_000))
    Column(Modifier.fillMaxWidth().testTag("character-filter-fields")) {
        ChoiceRow("字数范围", presets.map { it.first },
            presets.indexOfFirst { value.minimum == it.second.minimum && value.maximum == it.second.maximum }) {
            onChange(presets[it].second.copy(includeUnknown = value.includeUnknown))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("自定义范围", style = MaterialTheme.typography.titleSmall)
                    val minimumField: @Composable (Modifier) -> Unit = { modifier ->
                        AppTextField(minimum, { minimum = it.filter { char -> char in '0'..'9' }.take(12) },
                            label = { Text("最少字数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = modifier.testTag("characters-minimum"))
                    }
                    val maximumField: @Composable (Modifier) -> Unit = { modifier ->
                        AppTextField(maximum, { maximum = it.filter { char -> char in '0'..'9' }.take(12) },
                            label = { Text("最多字数") }, singleLine = true, isError = invalid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = modifier.testTag("characters-maximum"))
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if(maxWidth < (240 * LocalDensity.current.fontScale).dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                minimumField(Modifier.fillMaxWidth())
                                maximumField(Modifier.fillMaxWidth())
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            minimumField(Modifier.weight(1f))
                            maximumField(Modifier.weight(1f))
                        }
                    }
                    Text(if(invalid) "最多字数不能小于最少字数。" else "单位：字 · 包含上下限 · 留空不限", style = MaterialTheme.typography.bodySmall,
                        color = if(invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    AppFilledTonalButton(onClick = { onChange(CharacterCountFilter(min, max, value.includeUnknown)) }, enabled = !invalid,
                        modifier = Modifier.fillMaxWidth().testTag("apply-character-filter")) { Text("应用字数范围") }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value.includeUnknown, role = Role.Switch,
                onValueChange = { onChange(value.copy(includeUnknown = it)) }).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("包含字数未知作品", style = MaterialTheme.typography.bodyLarge)
                    Text("未提供字数或获取失败时仍显示", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppSwitch(value.includeUnknown, onCheckedChange = null)
            }
        }
    }
}
