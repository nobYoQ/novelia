package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.validReaderTextReplacements
import cc.novelia.app.reader.ReaderTextReplacer
import cc.novelia.app.ui.components.base.*

@Composable internal fun ReaderReplacementsSheet(settings: ReaderSettings, sample: String,
    onDismiss: () -> Unit, onChange: (ReaderSettings) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var editor by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var preview by rememberSaveable { mutableStateOf(false) }
    val entries = remember(settings.textReplacements, query) {
        settings.textReplacements.entries.filter { it.key.contains(query, true) || it.value.contains(query, true) }.sortedBy { it.key }
    }
    ReaderSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(.85f).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("本书文字替换", style = MaterialTheme.typography.titleLarge)
            Text("只改变阅读显示，不修改原文件或原站译文。保存后使用本书阅读设置。", style = MaterialTheme.typography.bodySmall)
            TogglePreference("启用文字替换", "精确匹配，长词优先；正文、搜索和朗读保持一致", settings.textReplacementsEnabled,
                { onChange(settings.copy(textReplacementsEnabled = it)) })
            OutlinedTextField(query, { query = it }, label = { Text("查找替换规则") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextButton(onClick = { editingKey = null; source = ""; target = ""; editor = true }) { Text("添加规则") }
                AppTextButton(onClick = { preview = !preview }, enabled = sample.isNotEmpty()) { Text(if(preview) "收起预览" else "预览当前段") }
                AppTextButton(onClick = onDismiss) { Text("完成") }
            }
            AppLazyColumn(Modifier.weight(1f).testTag("reader-replacements-list")) {
                if(preview) item {
                    val text = remember(sample, settings.activeTextReplacements) { ReaderTextReplacer(settings.activeTextReplacements).replace(sample) ?: sample }
                    Text("替换前：$sample", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    Text("显示为：$text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("reader-replacement-preview"))
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                }
                if(entries.isEmpty()) item { Text("暂无匹配规则。可统一人名、译名或纠正显示用词。", Modifier.padding(vertical = 20.dp)) }
                items(entries, key = { it.key }) { rule ->
                    ListItem(headlineContent = { Text(rule.key) }, supportingContent = { Text("→ ${rule.value}") }, trailingContent = {
                        Row {
                            AppIconButton(onClick = { editingKey = rule.key; source = rule.key; target = rule.value; editor = true }) { Icon(Icons.Outlined.Edit, "编辑 ${rule.key}") }
                            AppIconButton(onClick = { onChange(settings.copy(textReplacements = settings.textReplacements - rule.key)) }) { Icon(Icons.Outlined.DeleteOutline, "删除 ${rule.key}") }
                        }
                    })
                }
            }
            Text("${settings.textReplacements.size} / 1000 条 · 单项最多 120 字符 · 不使用正则表达式", style = MaterialTheme.typography.labelSmall)
        }
    }
    if(editor) {
        val rules = (settings.textReplacements - editingKey.orEmpty()) + (source.trim() to target.trim())
        val duplicate = source.trim() != editingKey && source.trim() in settings.textReplacements
        val valid = validReaderTextReplacements(rules) && !duplicate
        AppAlertDialog(onDismissRequest = { editor = false }, title = { Text(if(editingKey == null) "添加替换规则" else "编辑替换规则") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(source, { source = it }, label = { Text("要替换的文字") }, singleLine = true, modifier = Modifier.testTag("reader-replacement-source"))
                OutlinedTextField(target, { target = it }, label = { Text("显示为") }, singleLine = true, modifier = Modifier.testTag("reader-replacement-target"))
                if(duplicate) Text("已有相同文字的规则，请编辑原规则。", color = MaterialTheme.colorScheme.error)
                Text("按当前显示文本匹配，区分大小写；替换结果不会再次替换。", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { AppTextButton(onClick = { onChange(settings.copy(textReplacements = rules)); editor = false }, enabled = valid) { Text("保存到本书") } },
            dismissButton = { AppTextButton(onClick = { editor = false }) { Text("取消") } })
    }
}
