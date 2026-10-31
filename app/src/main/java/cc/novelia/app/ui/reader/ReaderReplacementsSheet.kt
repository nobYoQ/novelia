package cc.novelia.app.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.*
import cc.novelia.app.reader.ReaderTextReplacer
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun ReaderReplacementsSheet(settings: ReaderSettings, sample: String,
    onDismiss: () -> Unit, onChange: (ReaderSettings) -> Unit, initialSource: String? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    var editor by rememberSaveable { mutableStateOf(initialSource != null) }
    var editingKey by rememberSaveable { mutableStateOf(initialSource?.takeIf { it in settings.textReplacements }) }
    var source by rememberSaveable { mutableStateOf(initialSource.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(settings.textReplacements[initialSource].orEmpty()) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val entries = remember(settings.textReplacements, query) {
        settings.textReplacements.entries.filter { it.key.contains(query, true) || it.value.contains(query, true) }.sortedBy { it.key }
    }
    fun closeEditor() { focus.clearFocus(); editor = false }
    fun edit(key: String? = null) {
        editingKey = key; source = key.orEmpty(); target = settings.textReplacements[key].orEmpty(); editor = true
    }
    ReaderSheet(onDismissRequest = { if(editor) closeEditor() else onDismiss() }) {
        BackHandler(editor) { closeEditor() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(if(appReducedMotion()) 1f else .88f).imePadding().testTag("reader-replacements-panel")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if(editor) AppIconButton(onClick = ::closeEditor) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回替换规则") }
                else Icon(Icons.Outlined.FindReplace, null, Modifier.padding(horizontal = 12.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if(editor) if(editingKey == null) "添加替换规则" else "编辑替换规则" else "本书文字替换", style = MaterialTheme.typography.titleLarge)
                    Text("仅本书生效 · 正文、搜索与朗读共用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AppTextButton(onClick = { if(editor) closeEditor() else onDismiss() }) { Text(if(editor) "取消" else "完成") }
            }
            if(editor) {
                val from = source.trim()
                val to = target.trim()
                val rules = (settings.textReplacements - editingKey.orEmpty()) + (from to to)
                val duplicate = from != editingKey && from in settings.textReplacements
                val identical = from.isNotEmpty() && from == to
                val valid = validReaderTextReplacements(rules) && !duplicate && !identical &&
                    source.length <= MAX_READER_REPLACEMENT_LENGTH && target.length <= MAX_READER_REPLACEMENT_LENGTH &&
                    source.none(Char::isISOControl) && target.none(Char::isISOControl)
                val error = when {
                    duplicate -> "已有这个原词，请返回列表编辑现有规则。"
                    identical -> "新文字与原文字相同，无需替换。"
                    source.length > MAX_READER_REPLACEMENT_LENGTH || target.length > MAX_READER_REPLACEMENT_LENGTH -> "每项最多 120 个字符。"
                    source.any(Char::isISOControl) || target.any(Char::isISOControl) -> "请使用单行文字，不包含换行或控制字符。"
                    rules.size > MAX_READER_REPLACEMENTS -> "已达到 1000 条规则上限，请先删除不再使用的规则。"
                    from.isNotBlank() && to.isNotBlank() && !valid -> "规则总长度已达上限，请精简后保存。"
                    else -> null
                }
                AppScrollColumn(Modifier.weight(1f), contentModifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReplacementStep("1", "找到原文字", "与正文一致，区分大小写")
                    OutlinedTextField(source, { source = it }, label = { Text("要替换的文字") }, placeholder = { Text("例如：爱丽丝") },
                        singleLine = true, isError = duplicate || source.length > MAX_READER_REPLACEMENT_LENGTH,
                        modifier = Modifier.fillMaxWidth().testTag("reader-replacement-source"), supportingText = { Text("${source.length} / 120") })
                    ReplacementStep("2", "填写新文字", "保存后，用这个词替代原文字")
                    OutlinedTextField(target, { target = it }, label = { Text("显示为") }, placeholder = { Text("例如：艾莉丝") },
                        singleLine = true, isError = identical || target.length > MAX_READER_REPLACEMENT_LENGTH,
                        modifier = Modifier.fillMaxWidth().testTag("reader-replacement-target"), supportingText = { Text("${target.length} / 120") })
                    if(error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    val matchCount = remember(sample, from) { if(from.isEmpty()) 0 else Regex(Regex.escape(from)).findAll(sample).count() }
                    val context = remember(sample, from) {
                        val index = sample.indexOf(from).coerceAtLeast(0)
                        val start = (index - 48).coerceAtLeast(0)
                        val end = (index + from.length + 100).coerceAtMost(sample.length)
                        if(sample.isBlank() || (from.isNotBlank() && !sample.contains(from))) from
                        else (if(start > 0) "…" else "") + sample.substring(start, end) + if(end < sample.length) "…" else ""
                    }
                    val result = remember(context, from, to, valid) {
                        if(from.isBlank() || to.isBlank() || !valid) context else ReaderTextReplacer(mapOf(from to to)).replace(context) ?: context
                    }
                    ReplacementPreview(context.ifBlank { "爱丽丝来到小镇。" },
                        if(from.isBlank() || to.isBlank()) "填写上方两项，即可预览替换效果" else result,
                        if(matchCount > 0) "当前段落找到 $matchCount 处" else if(from.isBlank()) "效果预览" else "词语预览 · 当前段落未找到原词")
                    Text("只调整本书的显示文字，原文件与原站译文会保留。也可以在正文长按选词，再点「替换选文」直接带入原词。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if(!settings.textReplacementsEnabled) Text("文字替换已关闭，保存后可在列表中开启。", style = MaterialTheme.typography.bodySmall)
                    AppButton(onClick = { onChange(settings.copy(textReplacements = rules)); query = ""; closeEditor() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = valid) { Text("保存到本书") }
                }
            } else {
                AppLazyColumn(Modifier.weight(1f).testTag("reader-replacements-list"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(shape = appRoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            TogglePreference("启用文字替换", if(settings.textReplacementsEnabled) "已开启 · ${settings.textReplacements.size} 条规则" else "已关闭 · 规则仍会保留",
                                settings.textReplacementsEnabled) { onChange(settings.copy(textReplacementsEnabled = it)) }
                        }
                    }
                    if(settings.textReplacements.isEmpty()) item {
                        Surface(shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("把译名改成你熟悉的写法", style = MaterialTheme.typography.titleMedium)
                                Text("爱丽丝  →  艾莉丝", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                                Text("添加原词和新词，预览后保存。本书中相同的文字会一起替换。", style = MaterialTheme.typography.bodyMedium)
                                Text("快捷方法：正文长按选词 → 替换选文", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if(settings.textReplacements.isNotEmpty()) item {
                        OutlinedTextField(query, { query = it }, label = { Text("查找原词或替换词") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
                    }
                    if(sample.isNotBlank() && settings.textReplacements.isNotEmpty()) item {
                        AppTextButton(onClick = { preview = !preview }) {
                            Icon(Icons.Outlined.CompareArrows, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Text(if(preview) "收起预览" else "预览当前段")
                        }
                        if(preview) ReplacementPreview(sample,
                            remember(sample, settings.activeTextReplacements) { ReaderTextReplacer(settings.activeTextReplacements).replace(sample) ?: sample },
                            if(settings.textReplacementsEnabled) "当前段落效果" else "替换已关闭，显示原文")
                    }
                    if(entries.isEmpty() && settings.textReplacements.isNotEmpty()) item {
                        Text("没有找到匹配的规则，试试原词或新词。", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(entries, key = { it.key }) { rule ->
                        Surface(onClick = { edit(rule.key) }, shape = appRoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(rule.key, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.SubdirectoryArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(rule.value, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                AppIconButton(onClick = { edit(rule.key) }) { Icon(Icons.Outlined.Edit, "编辑 ${rule.key}") }
                                AppIconButton(onClick = { deleting = rule.key }) { Icon(Icons.Outlined.DeleteOutline, "删除 ${rule.key}") }
                            }
                        }
                    }
                    item { Text("精确匹配 · 长词优先 · 替换结果不再重复替换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("${settings.textReplacements.size} / 1000 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppButton(onClick = { edit() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = settings.textReplacements.size < MAX_READER_REPLACEMENTS) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加规则")
                    }
                }
            }
        }
    }
    deleting?.let { key -> ConfirmDialog("删除这条替换规则？", "「$key」将恢复原来的显示文字。", { deleting = null }, "删除规则") {
        onChange(settings.copy(textReplacements = settings.textReplacements - key)); deleting = null
    } }
}

@Composable private fun ReplacementStep(number: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = appRoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Text(number, style = MaterialTheme.typography.titleMedium) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ReplacementPreview(before: String, after: String, title: String) {
    Surface(shape = appRoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("替换前：$before", style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("显示为：$after", style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("reader-replacement-preview"))
        }
    }
}
