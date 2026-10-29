package cc.novelia.app.ui.notes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cc.novelia.app.files.exporting.PendingExportFiles
import cc.novelia.app.ui.navigation.AppController
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*

internal data class NoteExportState(val busy: Boolean, val export: (List<NotePresentation>, NoteExportFormat) -> Unit)

/** 载荷先暂存到磁盘，文件选择期间旋转或重建不会改用新的筛选结果。 */
@Composable internal fun rememberNoteExport(c: AppController): NoteExportState {
    val scope = rememberCoroutineScope()
    val directory = remember(c.app) { File(c.app.cacheDir, "note-exports") }
    val files = remember(directory) { PendingExportFiles(directory) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val complete: (android.net.Uri?) -> Unit = { uri ->
        val id = pending
        pending = null
        if(id == null) { if(uri != null) c.message("待导出内容已丢失，请重新导出") }
        else scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val destination: (() -> java.io.OutputStream?)? = uri?.let { target -> { c.app.contentResolver.openOutputStream(target, "wt") } }
                    val job = currentCoroutineContext()
                    files.finish(id, destination) { job.ensureActive() }
                }
                if(uri != null) c.message("书签与批注已导出")
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { c.message("导出失败，请重新选择保存位置") }
            finally { busy = false }
        }
    }
    val markdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(NoteExportFormat.Markdown.mime), complete)
    val text = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(NoteExportFormat.Text.mime), complete)
    return NoteExportState(busy || pending != null) { notes, format ->
        if(!busy && pending == null && notes.isNotEmpty()) {
            val snapshot = notes.toList()
            busy = true
            scope.launch {
                var id: String? = null
                var handedOff = false
                try {
                    withContext(Dispatchers.IO) {
                        directory.mkdirs()
                        val job = currentCoroutineContext()
                        id = files.create(exportNotes(snapshot, format) { job.ensureActive() }.toByteArray(Charsets.UTF_8)) { job.ensureActive() }
                    }
                    pending = id
                    val name = "novelia-notes-${LocalDate.now()}.${format.extension}"
                    if(format == NoteExportFormat.Markdown) markdown.launch(name) else text.launch(name)
                    handedOff = true
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { c.message(e.message ?: "无法准备导出内容") }
                finally {
                    if(!handedOff) {
                        pending = null
                        withContext(NonCancellable + Dispatchers.IO) { id?.let { files.finish(it, null) } }
                    }
                    busy = false
                }
            }
        }
    }
}
