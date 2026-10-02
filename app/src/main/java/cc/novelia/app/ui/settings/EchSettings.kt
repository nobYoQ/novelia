@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.network.EchTransport
import cc.novelia.app.files.PendingExportFiles
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.TogglePreference
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun EchSettings(transport: EchTransport, onDismiss: () -> Unit) {
    val enabled by transport.enabled.collectAsStateWithLifecycle()
    val recording by transport.recording.collectAsStateWithLifecycle()
    val diagnosis by transport.diagnosis.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exportDirectory = remember(context) { File(context.cacheDir, "network-exports") }
    val exportFiles = remember(exportDirectory) { PendingExportFiles(exportDirectory) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (id == null) { if (uri != null) notice = "日志暂存文件已丢失，请重新导出" }
        else scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val destination: (() -> java.io.OutputStream?)? = uri?.let { target -> { context.contentResolver.openOutputStream(target) } }
                    exportFiles.finish(id, destination)
                }
                if (uri != null) notice = "日志已导出，可将 ZIP 文件发送给开发者分析"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "日志导出失败，请重新选择保存位置" }
            finally { exporting = false }
        }
    }
    AppSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("网络诊断与日志", style = MaterialTheme.typography.titleLarge)
            TogglePreference("启用 ECH", "对原站小说、论坛和认证接口使用加密握手；镜像线路使用普通 HTTPS", enabled, transport::setEnabled)
            Text("检测针对原站，对照直连和 ECH 的实际接口请求，最长约 3 分钟。关闭此面板后仍会继续，可返回查看结果。", style = MaterialTheme.typography.bodyMedium)
            Button(enabled = !diagnosis.running, onClick = transport::startDiagnostics) { Text("运行网络诊断") }
            if (diagnosis.running) {
                Text("正在检测 ${diagnosis.completed}/${diagnosis.total}")
                TextButton(onClick = transport::cancelDiagnostics) { Text("取消诊断") }
            }
            HorizontalDivider()
            TogglePreference("记录问题复现过程", "开启后返回出错页面重试，再回来导出；30 分钟后自动停止", recording, transport::setRecording)
            Text("日志最多约 1 MiB，仅含连接阶段、耗时、接口类别、服务器地址和设备网络概况，不含账号、令牌、Cookie、搜索词及正文；不会自动上传。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !exporting && pendingExportId == null, onClick = {
                exporting = true; notice = ""
                scope.launch {
                    try {
                        val bytes = transport.exportNetworkLogs()
                        pendingExportId = withContext(Dispatchers.IO) {
                            exportDirectory.mkdirs()
                            exportDirectory.listFiles()?.filter { it.name.startsWith("tool-export-") && it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
                            exportFiles.create(bytes)
                        }
                        exporter.launch("novelia-network-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.zip")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        val id = pendingExportId; pendingExportId = null
                        if (id != null) withContext(Dispatchers.IO) { runCatching { exportFiles.finish(id, null) } }
                        notice = "日志准备失败，请稍后重试"
                    }
                    finally { exporting = false }
                }
            }) { Text(if (exporting) "正在导出…" else "导出网络日志") }
            TextButton(enabled = !diagnosis.running && !exporting, onClick = {
                scope.launch {
                    try { transport.clearNetworkLogs(); notice = "网络日志已清空" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "清空失败，请稍后重试" }
                }
            }) { Text("清空网络日志") }
            if (notice.isNotEmpty()) Text(notice, style = MaterialTheme.typography.bodyMedium)
            if (diagnosis.report.isNotEmpty()) {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("网络诊断", diagnosis.report))
                }) { Text("复制诊断结果") }
                Text(diagnosis.report, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
