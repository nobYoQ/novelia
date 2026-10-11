package cc.novelia.app.ui.settings

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.webdav.*
import cc.novelia.app.ui.components.base.ChoiceRow
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal val webDavLabels = mapOf(
    SyncDomain.SETTINGS to ("本地设置" to "阅读偏好、外观与屏蔽规则"),
    SyncDomain.KEYWORDS to ("标签库" to "标签、译名、分类与排列顺序"),
    SyncDomain.FAVORITES to ("本地收藏" to "网络书目、收藏夹、置顶与阅读状态"),
    SyncDomain.PROGRESS to ("阅读进度" to "接续网络书目的章节与文字位置"),
    SyncDomain.BOOKMARKS to ("书签" to "保存网络书目的书签与定位"),
    SyncDomain.NOTES to ("笔记" to "同步网络书目的摘录与笔记正文"),
    SyncDomain.HISTORY to ("阅读历史" to "最近阅读的网络书目与阅读时间"),
)

@Composable
fun WebDavSyncScreen(c: AppController) {
    val config by c.app.webDavConfig.config.collectAsStateWithLifecycle()
    val status by c.app.webDav.status.collectAsStateWithLifecycle()
    val library by c.store.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<WebDavPreview?>(null) }
    var settingsPreference by remember { mutableStateOf(BootstrapPreference.LOCAL_SETTINGS) }
    var conflict by remember { mutableStateOf<SyncConflict?>(null) }
    var recovery by remember { mutableStateOf<WebDavRecovery?>(null) }
    val working = busy || status.running

    fun conflictTitle(value: SyncConflict): String {
        val title = if(value.domain == SyncDomain.NOTES) library.notes.firstOrNull { it.id == value.recordKey }?.let {
            listOf(it.bookTitle, it.chapterTitle).filter(String::isNotBlank).joinToString(" · ")
        } else library.books.firstOrNull { it.book.ref.key == value.recordKey }?.book?.title
            ?: library.readingHistory[value.recordKey]?.bookTitle
        return listOf(webDavLabels[value.domain]?.first ?: "同步资料", title.orEmpty().ifBlank { value.recordKey }).joinToString(" · ")
    }

    fun runTask(success: String? = null, task: suspend () -> Unit) {
        if(busy) return
        busy = true
        c.action {
            try { task(); success?.let(c::message) }
            finally { busy = false }
        }
    }
    fun save(value: WebDavConfig) = runTask {
        withContext(Dispatchers.IO) { c.app.webDavConfig.save(value) }
    }
    fun showPreview() = runTask {
        preview = c.app.webDav.preview()
        settingsPreference = BootstrapPreference.LOCAL_SETTINGS
    }

    // 开关即刻保存；首次加入及新增类型仍须预览。
    WebDavSyncContent(config, status, c.app.webDav.needsConfirmation(), working, WebDavSyncActions(
        onBack = c::back,
        onServer = { c.go("webdav-server") },
        onEnabled = { enabled ->
            if(!enabled) c.action("已停用同步") { withContext(Dispatchers.IO) { c.app.webDavConfig.disable() } }
            else if(config.endpoint.isBlank()) c.go("webdav-server")
            else save(config.copy(enabled = enabled))
        },
        onDomain = { domain, selected ->
            val next = if(selected) config.selected + domain else config.selected - domain
            val wasEnabled = config.enabled
            val updated = config.copy(selected = next, enabled = wasEnabled && next.isNotEmpty())
            runTask {
                withContext(Dispatchers.IO) { c.app.webDavConfig.save(updated) }
                if(next.isEmpty() && wasEnabled) c.message("未选择同步内容，已停用同步")
            }
        },
        onDevicePreferences = { save(config.copy(syncDevicePreferences = it)) },
        onAutomatic = { save(config.copy(automatic = it)) },
        onWifiOnly = { save(config.copy(wifiOnly = it)) },
        onInterval = { save(config.copy(intervalMinutes = it)) },
        onSync = {
            if(config.endpoint.isBlank()) c.go("webdav-server")
            else runTask {
                if(!config.enabled) withContext(Dispatchers.IO) { c.app.webDavConfig.save(config.copy(enabled = true)) }
                if(c.app.webDav.needsConfirmation()) {
                    preview = c.app.webDav.preview()
                    settingsPreference = BootstrapPreference.LOCAL_SETTINGS
                } else c.app.webDav.synchronize(manual = true)
            }
        },
        onPreview = ::showPreview,
        onRecover = { recovery = it },
        onConflict = { conflict = it },
        conflictTitle = ::conflictTitle,
    ))

    recovery?.takeIf { it.matches(config) }?.let { value ->
        WebDavReconnectDialog(working, onDismiss = { recovery = null }, onConfirm = {
            runTask {
                c.app.webDav.prepareReconnect(value)
                recovery = null
                preview = c.app.webDav.preview()
                settingsPreference = BootstrapPreference.LOCAL_SETTINGS
            }
        })
    }

    preview?.let { value ->
        AlertDialog(
            onDismissRequest = { if(!working) preview = null },
            title = { Text(if(value.existing) "连接已有同步资料" else "建立同步资料") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if(value.existing) "收藏、标签和阅读资料将按记录合并。本机没有某项资料不会删除其他设备的数据。" else "此目录尚未建立同步资料，将上传本机选中的内容。")
                value.items.forEach { item ->
                    Text("${webDavLabels.getValue(item.domain).first}：本机 ${item.localCount} 项，远端 ${item.remoteCount} 项" + if(item.conflicts > 0) "，${item.conflicts} 项需保留候选" else "")
                }
                if(value.existing && SyncDomain.SETTINGS in config.selected) {
                    Text("同一设置项有不同取值时：")
                    ChoiceRow("首次设置", listOf("保留本机", "使用远端"), if(settingsPreference == BootstrapPreference.LOCAL_SETTINGS) 0 else 1) { settingsPreference = if(it == 0) BootstrapPreference.LOCAL_SETTINGS else BootstrapPreference.REMOTE_SETTINGS }
                }
            } },
            confirmButton = { AppTextButton(onClick = { runTask("已连接同步资料") { c.app.webDav.connect(settingsPreference); preview = null } }, enabled = !working) { Text("合并并连接") } },
            dismissButton = { AppTextButton(onClick = { preview = null }, enabled = !working) { Text("取消") } },
        )
    }
    conflict?.let { value ->
        AlertDialog(
            onDismissRequest = { if(!working) conflict = null },
            title = { Text(conflictTitle(value)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择要使用的版本。选中的资料将在下一次同步时发送到其他设备。")
                value.candidates.forEachIndexed { index, candidate ->
                    var expanded by remember(candidate) { mutableStateOf(false) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("保留的版本 ${index + 1}", style = MaterialTheme.typography.titleSmall)
                            Text(if(candidate.deleted) "此版本已删除这项资料" else readableCandidate(candidate.value, expanded), style = MaterialTheme.typography.bodyMedium)
                            if(!candidate.deleted && candidate.value.toString().length > 2000) AppTextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "收起内容" else "显示完整内容") }
                            AppTextButton(onClick = { runTask("已恢复选中的版本") { c.app.webDav.resolveConflict(value, index); conflict = null } }, enabled = !working) { Text("使用此版本") }
                        }
                    }
                }
            } },
            confirmButton = { AppTextButton(onClick = { conflict = null }, enabled = !working) { Text("稍后处理") } },
        )
    }
}

@Composable
internal fun WebDavReconnectDialog(working: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if(!working) onDismiss() },
        title = { Text("重新连接同步目录？") },
        text = { Text("本机资料和服务器登录信息会保留。接下来将重新检查当前目录：目录为空时可用本机资料建立同步；已有同步资料时会先展示合并预览。如果重新建立了云端资料，其他设备也需重新连接。") },
        confirmButton = { AppTextButton(onClick = onConfirm, enabled = !working) { Text("重新检查并预览") } },
        dismissButton = { AppTextButton(onClick = onDismiss, enabled = !working) { Text("取消") } },
    )
}

/** 将保留的进度/正文转为可阅读内容，避免把同步协议暴露在选择流程里。 */
private fun readableCandidate(candidate: JsonElement, expanded: Boolean): String {
    val labels = mapOf("chapter" to "章节", "chapterId" to "章节", "chapterTitle" to "章节名", "paragraph" to "段落", "sourceParagraph" to "段落", "textOffset" to "文字位置", "offset" to "文字位置", "position" to "位置", "text" to "正文", "content" to "正文", "quote" to "摘录", "note" to "笔记", "title" to "章节名", "bookTitle" to "书名")
    val lines = mutableListOf<String>()
    fun content(value: String) = if(expanded || value.length <= 2000) value else value.take(2000) + "…（内容已折叠）"
    fun visit(element: JsonElement) {
        when(element) {
            is JsonObject -> element.forEach { (key, value) ->
                val label = labels[key]
                if(label != null && value is JsonPrimitive && value !is JsonNull && value.content.isNotBlank()) {
                    val display = if(key == "sourceParagraph") value.intOrNull?.let { (it + 1).toString() } ?: value.content else value.content
                    lines += "$label：${content(display)}"
                }
                else if(value is JsonObject || value is JsonArray) visit(value)
            }
            is JsonArray -> element.forEach(::visit)
            is JsonPrimitive -> if(element !is JsonNull && element.content.isNotBlank()) lines += content(element.content)
        }
    }
    visit(candidate)
    return lines.distinct().take(12).joinToString("\n").ifBlank { "此版本保留了书目与阅读定位资料" }
}
