@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.theme.DefaultShapes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appShape

/** 三个书架分区共用紧凑面板，草稿由调用方保存，底部确认区始终可达。 */
@Composable internal fun ShelfFilterSheet(title: String, prefix: String, maxHeight: Dp, valid: Boolean = true,
    onDismiss: () -> Unit, onReset: () -> Unit, onApply: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSheet(onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = maxHeight).imePadding().testTag("$prefix-filter-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                AppIconButton(onDismiss, Modifier.testTag("$prefix-filter-close"), shape = DefaultShapes.medium) {
                    Icon(Icons.Outlined.Close, "关闭筛选", Modifier.size(20.dp))
                }
            }
            AppScrollColumn(Modifier.weight(1f, fill = false),
                contentModifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp), content = content)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppOutlinedButton(onReset, Modifier.weight(1f).heightIn(min = 48.dp).testTag("$prefix-filter-reset"),
                    shape = RoundedCornerShape(12.dp)) { Text("重置") }
                AppButton(onApply, Modifier.weight(2f).heightIn(min = 48.dp).testTag("$prefix-filter-apply"), enabled = valid,
                    shape = RoundedCornerShape(12.dp)) { Text("应用筛选") }
            }
        }
    }
}

@Composable internal fun ShelfFilterOptions(title: String, labels: List<String>, selectedIndices: Set<Int>, tag: String,
    preferredColumns: Int = 4, multiple: Boolean = false, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val longest = labels.maxOfOrNull { it.length } ?: 1
            val minimum = ((longest * 13 + 24) * LocalDensity.current.fontScale).dp
            val fit = ((maxWidth + 8.dp) / (minimum + 8.dp)).toInt().coerceIn(1, preferredColumns.coerceAtMost(labels.size).coerceAtLeast(1))
            val columns = if(labels.size == 4 && fit == 3) 2 else fit
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.indices.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { index ->
                            val chosen = index in selectedIndices
                            Surface(onClick = { onSelect(index) }, shape = appShape(RoundedCornerShape(10.dp)),
                                color = if(chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if(chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = if(chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f)) else null,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("$tag-${labels[index]}").semantics {
                                    selected = chosen; role = if(multiple) Role.Checkbox else Role.RadioButton
                                }) {
                                Box(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(labels[index], style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** 字数常用档位直接选择，自定义输入按需展开，避免默认面板堆满表单。 */
@Composable internal fun ShelfCharacterFilter(value: CharacterCountFilter, onChange: (CharacterCountFilter) -> Unit,
    onValidity: (Boolean) -> Unit) {
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
        ShelfFilterOptions("字数范围", listOf("不限", "10 万以下", "10～50 万", "50 万及以上"), setOf(preset), "shelf-character-presets", 2) {
            minimum = presets[it].minimum?.toString().orEmpty(); maximum = presets[it].maximum?.toString().orEmpty()
            onChange(presets[it].copy(includeUnknown = value.includeUnknown)); onValidity(true)
        }
        AppTextButton({ custom = !custom }, Modifier.fillMaxWidth().testTag("characters-custom"), shape = DefaultShapes.small,
            contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("自定义字数", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        if(custom) {
            val field: @Composable (Boolean, Modifier) -> Unit = { lower, modifier ->
                AppTextField(if(lower) minimum else maximum,
                    { input -> val digits = input.filter { it in '0'..'9' }.take(12); if(lower) minimum = digits else maximum = digits },
                    label = { Text(if(lower) "最少字数" else "最多字数") }, singleLine = true, isError = !lower && invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = modifier.testTag(if(lower) "characters-minimum" else "characters-maximum"))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if(maxWidth < (240 * LocalDensity.current.fontScale).dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(true, Modifier.fillMaxWidth()); field(false, Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { field(true, Modifier.weight(1f)); field(false, Modifier.weight(1f)) }
            }
            Text(if(invalid) "最多字数不能小于最少字数" else "单位：字，留空不限", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = if(invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("包含字数未知作品", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            AppSwitch(value.includeUnknown, { onChange(value.copy(includeUnknown = it)) })
        }
    }
}
