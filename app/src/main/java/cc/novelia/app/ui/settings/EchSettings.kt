@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.network.EchTransport
import cc.novelia.app.files.exporting.PendingExportFiles
import cc.novelia.app.ui.components.base.AppSheet
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun EchSettings(transport: EchTransport, onDismiss: () -> Unit) {
    val recording by transport.recording.collectAsStateWithLifecycle()
    val diagnosis by transport.diagnosis.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
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
    // 放在 AppSheet 外，切换减少动效模式时仍保留页签和展开状态。
    val panelState = rememberNetworkDiagnosticsPanelState(recording)
    AppSheet(onDismissRequest = onDismiss) {
        NetworkDiagnosticsPanel(
            state = panelState,
            recording = recording,
            diagnosis = diagnosis,
            exporting = exporting || pendingExportId != null,
            clearing = clearing,
            notice = notice,
            onRecordingChange = transport::setRecording,
            onStart = transport::startDiagnostics,
            onCancel = transport::cancelDiagnostics,
            onDismiss = onDismiss,
            onCopy = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("网络诊断", diagnosis.report))
                notice = "诊断结果已复制"
            },
            onExport = {
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
            },
            onClear = {
                clearing = true
                scope.launch {
                    try {
                        transport.clearNetworkLogs()
                        panelState.hasRecorded.value = false
                        notice = "网络日志已清空"
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "清空失败，请稍后重试" }
                    finally { clearing = false }
                }
            },
        )
    }
}
