@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.theme.appShape

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.model.ReaderCustomColors
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.theme.readerColors

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
                Text("这是一行句子", Modifier.padding(16.dp), fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * settings.lineHeight).sp)
            }
        }
        listOf(Triple("text", "字体颜色", palette.text), Triple("background", "背景颜色", palette.background),
            Triple("toolbar", "工具栏颜色", palette.toolbar)).forEach { (field, label, current) ->
            Surface(onClick = { editing = field }, shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().testTag("reader-color-$field")) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = rgb(current), shape = appShape(CircleShape),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.size(40.dp)) {}
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        Text(hex(current), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("选色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("点击打开取色盘，也可输入 #RRGGBB。工具栏透明度可在「翻页」中调整。", style = MaterialTheme.typography.bodySmall)
        AppTextButton(onClick = { onChange(settings.copy(customColors = defaults?.resolvedCustomColors ?: ReaderCustomColors())) }) {
            Text(if(defaults != null) "恢复默认阅读配色" else "重置自定义配色")
        }
    }
    editing?.let { field ->
        val current = when(field) { "text" -> palette.text; "background" -> palette.background; else -> palette.toolbar }
        val label = when(field) { "text" -> "字体颜色"; "background" -> "背景颜色"; else -> "工具栏颜色" }
        key(field) {
            ReaderColorPicker(label, current, onDismiss = { editing = null }, onApply = {
                update(field, it)
                editing = null
            })
        }
    }
}
