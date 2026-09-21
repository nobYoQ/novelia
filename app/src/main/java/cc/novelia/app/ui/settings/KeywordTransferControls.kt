package cc.novelia.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.catalog.KeywordLibraryFormat
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class KeywordTransferState(val busy: Boolean, val error: String?, val export: () -> Unit, val importLibrary: () -> Unit)

// Keep document launchers and the checked import alive even when their settings row scrolls off screen.
@Composable internal fun rememberKeywordTransfer(c: AppController): KeywordTransferState {
    var pending by remember { mutableStateOf<KeywordLibrary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) c.action {
            busy = true; error = null
            try {
                withContext(Dispatchers.IO) {
                    c.app.keywords.flush()
                    c.app.contentResolver.openOutputStream(uri)?.use { KeywordLibraryFormat.write(it, c.app.keywords.exportLibrary()) }
                        ?: kotlin.error("无法写入标签库文件")
                }
                c.message("标签库已导出")
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(failure: Exception) { error = failure.friendlyMessage() }
            finally { busy = false }
        }
    }
    val selectLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) c.action {
            busy = true; error = null
            try {
                pending = withContext(Dispatchers.IO) {
                    c.app.contentResolver.openInputStream(uri)?.use(KeywordLibraryFormat::read) ?: kotlin.error("无法读取标签库文件")
                }
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(failure: Exception) { error = failure.friendlyMessage() }
            finally { busy = false }
        }
    }
    pending?.let { incoming ->
        AppAlertDialog(onDismissRequest = { if(!busy) { pending = null; error = null } }, title = { Text("导入标签库") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("文件检查通过：${incoming.entries.size} 个标签、${incoming.categories.size} 个分类。")
                Text("合并到现有标签库。同名分类合并；相同原文的标签保留本机已编辑的译名和分类，其余内容从文件补充。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                c.action {
                    busy = true; error = null
                    try {
                        withContext(Dispatchers.IO) { c.app.keywords.mergeLibrary(incoming); c.app.keywords.flush() }
                        pending = null; c.message("标签库已合并导入")
                    } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch(failure: Exception) { error = failure.friendlyMessage() }
                    finally { busy = false }
                }
            }) { Text("合并导入标签库") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null; error = null }) { Text("取消") } })
    }
    return KeywordTransferState(busy, error.takeIf { pending == null },
        { if(!busy) export.launch("novelia-keywords-${java.time.LocalDate.now()}.json") },
        { if(!busy) selectLibrary.launch(arrayOf("application/json", "text/plain", "*/*")) })
}

@Composable internal fun KeywordTransferControls(state: KeywordTransferState) {
    Column {
        MenuRow("导出标签库", "标签原文、中文译名和自定义分类", Icons.Outlined.IosShare, state.export)
        MenuRow("导入标签库", "检查 JSON 文件后合并，保留本机已修改的译名和分类", Icons.Outlined.FileOpen, state.importLibrary)
        if(state.busy) Text("正在处理标签库…", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary)
        state.error?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
    }
}
