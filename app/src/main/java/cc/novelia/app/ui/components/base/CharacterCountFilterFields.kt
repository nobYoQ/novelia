package cc.novelia.app.ui.components.base

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import cc.novelia.app.ui.theme.DefaultShapes
import kotlinx.serialization.encodeToString

internal val NovelLocalFilterSaver = Saver<NovelLocalFilter, String>(
    save = { appJson.encodeToString(it) }, restore = { appJson.decodeFromString<NovelLocalFilter>(it).normalized() },
)
internal val CharacterCountFilterSaver = Saver<CharacterCountFilter, String>(
    save = { appJson.encodeToString(it) }, restore = { appJson.decodeFromString<CharacterCountFilter>(it).normalized() },
)

/** 字数常用档位直接选择，自定义输入按需展开，避免默认面板堆满表单。 */
@Composable internal fun CharacterCountFilterFields(value: CharacterCountFilter, onValidity: (Boolean) -> Unit = {},
    onChange: (CharacterCountFilter) -> Unit) {
    val presets = listOf(CharacterCountFilter(), CharacterCountFilter(maximum = 99_999),
        CharacterCountFilter(100_000, 499_999), CharacterCountFilter(minimum = 500_000))
    val preset = presets.indexOfFirst { it.minimum == value.minimum && it.maximum == value.maximum }
    var custom by rememberSaveable { mutableStateOf(preset < 0) }
    var minimum by rememberSaveable(value.minimum, value.maximum) { mutableStateOf(value.minimum?.toString().orEmpty()) }
    var maximum by rememberSaveable(value.minimum, value.maximum) { mutableStateOf(value.maximum?.toString().orEmpty()) }
    val min = minimum.toLongOrNull(); val max = maximum.toLongOrNull()
    val invalid = (minimum.isNotBlank() && min == null) || (maximum.isNotBlank() && max == null) || (min != null && max != null && min > max)
    LaunchedEffect(minimum, maximum, value.includeUnknown) {
        onValidity(!invalid)
        if(!invalid) {
            val next = CharacterCountFilter(min, max, value.includeUnknown)
            if(next != value) onChange(next)
        }
    }
    Column(Modifier.testTag("character-filter-fields")) {
        FilterOptions("字数范围", listOf("不限", "10 万以下", "10～50 万", "50 万及以上"), setOf(preset), "shelf-character-presets", 2) {
            minimum = presets[it].minimum?.toString().orEmpty(); maximum = presets[it].maximum?.toString().orEmpty()
            onChange(presets[it].copy(includeUnknown = value.includeUnknown)); onValidity(true)
        }
        AppTextButton({ custom = !custom }, Modifier.fillMaxWidth().testTag("characters-custom"), shape = DefaultShapes.small,
            contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(if(!custom && preset < 0) "自定义 · ${value.summary()}" else "自定义字数",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Icon(if(custom) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        if(custom) {
            val field: @Composable (Boolean, Modifier) -> Unit = { lower, modifier ->
                AppTextField(if(lower) minimum else maximum,
                    { input -> val digits = input.filter { it in '0'..'9' }.take(12); if(lower) minimum = digits else maximum = digits },
                    label = { Text(if(lower) "最少字数" else "最多字数") }, placeholder = { Text("不限") }, singleLine = true, isError = !lower && invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = modifier.testTag(if(lower) "characters-minimum" else "characters-maximum"))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if(maxWidth < (240 * LocalDensity.current.fontScale).dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(true, Modifier.fillMaxWidth()); field(false, Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { field(true, Modifier.weight(1f)); field(false, Modifier.weight(1f)) }
            }
        }
        if(invalid) Text("最多字数不能小于最少字数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value.includeUnknown, role = Role.Switch,
            onValueChange = { onChange(value.copy(includeUnknown = it)) }), verticalAlignment = Alignment.CenterVertically) {
            Text("包含字数未知作品", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            AppSwitch(value.includeUnknown, onCheckedChange = null)
        }
    }
}
