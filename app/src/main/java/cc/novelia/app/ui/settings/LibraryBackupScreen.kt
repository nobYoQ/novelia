package cc.novelia.app.ui.settings

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.account.ProfileDetailCard
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.backup.BackupPreview
import cc.novelia.app.data.backup.LibraryBackupService
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileToggle
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.navigation.AppController
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun LibraryBackupScreen(c: AppController) {
    val service = remember(c.store, c.app.keywords) { LibraryBackupService(c.store, c.app.keywords) }
    val recovery by c.store.recoveryIssue.collectAsStateWithLifecycle()
    var includeOriginals by rememberSaveable { mutableStateOf(false) }
    var stagingId by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var confirmLastGood by remember { mutableStateOf(false) }

    LaunchedEffect(stagingId) {
        val id = stagingId ?: return@LaunchedEffect
        try { preview = service.preview(id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.friendlyMessage(); preview = null; stagingId = null }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) c.action {
            busy = true; error = null
            try {
                withContext(Dispatchers.IO) {
                    c.app.contentResolver.openOutputStream(uri)?.use { service.export(it, includeOriginals) } ?: kotlin.error("无法写入备份文件")
                }
                c.message("阅读资料备份已导出")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.friendlyMessage() }
            finally { busy = false }
        }
    }
    val selectBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) c.action {
            busy = true; error = null
            try {
                val prepared = withContext(Dispatchers.IO) {
                    c.app.contentResolver.openInputStream(uri)?.use { service.prepare(it) } ?: kotlin.error("无法读取备份文件")
                }
                stagingId?.let { service.discard(it) }
                preview = prepared; stagingId = prepared.stagingId
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.friendlyMessage() }
            finally { busy = false }
        }
    }
    ProfileDetailScreen("阅读资料备份", if (recovery == null) c::back else null) { padding ->
        ProfileDetailList(Modifier.padding(padding)) {
            recovery?.let { issue -> item {
                ProfileDetailCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(issue.message)
                        if (issue.hasLastGood) AppButton(onClick = { confirmLastGood = true }, enabled = !busy) { Text("恢复最后良好副本") }
                    }
                }
            } }
            item { ProfileDetailCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("导出阅读资料", style = MaterialTheme.typography.titleLarge)
                    Text("保存书架、进度、笔记、阅读偏好、分卷设置、术语和标签。包含本地正文与插图。", style = MaterialTheme.typography.bodyMedium)
                    AppButton(onClick = { export.launch("novelia-library-${java.time.LocalDate.now()}.zip") }, enabled = !busy) { Text("导出备份") }
                }
            } }
            item { ProfileToggle("同时打包原始小说文件", "包含已有 EPUB、TXT、SRT 原件", includeOriginals, enabled = !busy) { includeOriginals = it } }
            item { ProfileSectionTitle("从备份恢复") }
            item { ProfileDetailCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择备份后先检查内容，再确认合并。", style = MaterialTheme.typography.bodyMedium)
                    AppOutlinedButton(onClick = { selectBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy) { Text("选择备份并检查") }
                }
            } }
            if (busy) item { Text("正在处理资料，请稍候…", color = MaterialTheme.colorScheme.primary) }
            error?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error) } }
            preview?.let { p -> item {
                ProfileDetailCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("备份检查通过", style = MaterialTheme.typography.titleMedium)
                    Text("创建于 ${DateFormat.getDateTimeInstance().format(Date(p.createdAt))}\n${p.books} 本书 · ${p.positions} 条进度 · ${p.notes} 条笔记\n${p.documents} 本本地正文 · ${p.keywords} 个标签\n正文与文件 ${"%.1f".format(p.bytes / 1024.0 / 1024.0)} MB${if (p.originalsIncluded) " · 包含已有原件" else ""}")
                    if (p.missingDocuments > 0) Text("${p.missingDocuments} 本本地书的内容在备份时已缺失，只能恢复记录。", color = MaterialTheme.colorScheme.error)
                    Text("合并到当前资料：同一本书、同 ID 笔记、单书设置与标签翻译保留本机版本，阅读进度选择更新时间较新的一条。新资料会加入书架；空资料库同时恢复全局偏好。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(onClick = { confirm = true }, enabled = !busy) { Text("合并恢复") }
                        AppTextButton(onClick = { val id = stagingId; stagingId = null; preview = null; if (id != null) c.action { service.discard(id) } }, enabled = !busy) { Text("取消") }
                    }
                } }
            } }
            item { Text("不包含登录会话、账号凭据、待同步操作、下载任务和网络章节缓存。备份单文件上限 128 MB，总计上限 1 GB。请将备份保存到设备之外，以便换机或卸载后恢复。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (confirm) ConfirmDialog("合并恢复阅读资料？", "将按上方规则合并备份。当前资料会保留；恢复完成前不要强制停止应用。", { confirm = false }, confirmLabel = "合并恢复资料") {
        val id = stagingId
        if (id != null) c.action {
            busy = true; error = null
            try {
                val warning = service.restore(id)
                stagingId = null; preview = null
                c.message(warning ?: "阅读资料已恢复")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.friendlyMessage() }
            finally { busy = false }
        }
    }
    if (confirmLastGood) ConfirmDialog("恢复最后良好副本？", "使用当前显示的良好副本恢复读写。损坏的原文件会另存保留，以便后续排查。", { confirmLastGood = false }, confirmLabel = "恢复良好副本") {
        c.action("已恢复最后良好副本") { busy = true; try { c.store.recoverLastGood() } finally { busy = false } }
    }
}
