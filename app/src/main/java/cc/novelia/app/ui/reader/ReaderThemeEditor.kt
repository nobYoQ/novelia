@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.novelia.app.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.model.ReaderCustomColors
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.theme.readerColors

private val ColorPresets = listOf(0x282E27L, 0x000000L, 0xFFFFFFL, 0xF4ECD8L, 0xE8F2E5L, 0x141A16L, 0xDDE5DCL, 0xDED2B8L)
private fun rgb(value: Long) = Color(0xFF000000L or value)
private fun hex(value: Long) = "#%06X".format(value)

@Composable internal fun ReaderThemeEditor(settings: ReaderSettings, defaults: ReaderSettings?, onChange: (ReaderSettings) -> Unit) {
    val palette = settings.resolvedCustomColors
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    fun update(field: String, color: Long) = onChange(settings.copy(customColors = when(field) {
        "text" -> palette.copy(text = color)
        "background" -> palette.copy(background = color)
        else -> palette.copy(toolbar = color)
    }))
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val colors = readerColors(settings, MaterialTheme.colorScheme)
        Surface(color = colors.background, contentColor = colors.foreground, shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().testTag("reader-color-preview")) {
            Column {
                Surface(color = colors.toolbar, contentColor = colors.foreground, modifier = Modifier.fillMaxWidth()) {
                    Text("阅读配色预览", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge)
                }
                Text("风穿过树梢，故事在下一页继续。", Modifier.padding(16.dp), fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp)
            }
        }
        listOf(Triple("text", "字体颜色", palette.text), Triple("background", "背景颜色", palette.background),
            Triple("toolbar", "工具栏颜色", palette.toolbar)).forEach { (field, label, current) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { editing = field }, modifier = Modifier.testTag("reader-color-$field")) { Text(hex(current)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorPresets.forEach { preset ->
                    Surface(onClick = { update(field, preset) }, color = rgb(preset), shape = CircleShape,
                        border = BorderStroke(if(current == preset) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "$label ${hex(preset)}"; selected = current == preset }) {
                        if(current == preset) Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, tint = if(rgb(preset).luminance() > .5f) Color.Black else Color.White)
                        }
                    }
                }
            }
        }
        Text("点击色块选择，或点击色值输入 #RRGGBB。工具栏透明度可在「翻页」中调整。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onChange(settings.copy(customColors = defaults?.resolvedCustomColors ?: ReaderCustomColors())) }) {
            Text(if(defaults != null) "恢复默认阅读配色" else "重置自定义配色")
        }
    }
    editing?.let { field ->
        val current = when(field) { "text" -> palette.text; "background" -> palette.background; else -> palette.toolbar }
        val label = when(field) { "text" -> "字体颜色"; "background" -> "背景颜色"; else -> "工具栏颜色" }
        key(field) {
            var input by rememberSaveable { mutableStateOf(hex(current)) }
            val digits = input.trim().removePrefix("#")
            val valid = digits.matches(Regex("[0-9a-fA-F]{6}"))
            AppAlertDialog(onDismissRequest = { editing = null }, title = { Text(label) }, text = {
                OutlinedTextField(input, { input = it }, label = { Text("十六进制颜色") }, singleLine = true,
                    isError = !valid, supportingText = { Text("输入 6 位 RGB 色值，例如 #F4ECD8") }, modifier = Modifier.testTag("reader-color-input"))
            }, confirmButton = {
                TextButton(onClick = { update(field, digits.toLong(16)); editing = null }, enabled = valid) { Text("应用颜色") }
            }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
        }
    }
}
