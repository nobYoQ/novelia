package cc.novelia.app.ui.book

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.exporting.*
import cc.novelia.app.sharing.shareBookFile
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.components.documents.CreateBookDocument
import cc.novelia.app.ui.navigation.AppController
import java.io.File
import kotlinx.coroutines.*

internal class MountedVolumeTransfer(val busy: Boolean, val start: (List<BookRef>, Boolean) -> Unit)

@Composable internal fun rememberMountedVolumeTransfer(c: AppController, book: BookCard): MountedVolumeTransfer {
    val files = remember(c.store) { BookExportFiles(c.store.exportsDir) }
    var pending by rememberSaveable(book.ref.key) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(CreateBookDocument()) { uri ->
        val id = pending ?: return@rememberLauncherForActivityResult
        pending = null
        working = true
        c.action(if(uri != null) "分卷文件已导出" else null) {
            try {
                withContext(Dispatchers.IO) {
                    val work = coroutineContext
                    files.finish(id, uri?.let { { c.app.contentResolver.openOutputStream(it, "wt") } }) { work.ensureActive() }
                }
            } finally { working = false }
        }
    }
    return MountedVolumeTransfer(working || pending != null) { refs, sharing ->
        if(!working && pending == null && refs.isNotEmpty()) {
            working = true
            scope.launch {
                var staged: PreparedBookExport? = null
                var shared: File? = null
                var handedOff = false
                try {
                    withContext(Dispatchers.IO) {
                        val prepared = files.prepareMounted(c.store, book, refs)
                        staged = prepared
                        if(sharing) shared = files.share(prepared.id, prepared.name.substringAfterLast('.'))
                    }
                    val prepared = checkNotNull(staged)
                    if(sharing) shareBookFile(c.app, checkNotNull(shared), bookFileMimeType(prepared.name), prepared.name, "分享分卷")
                    else {
                        pending = prepared.id
                        exporter.launch(prepared.name)
                    }
                    handedOff = true
                } catch(cancelled: CancellationException) {
                    throw cancelled
                } catch(error: Exception) {
                    c.message(error.friendlyMessage())
                } finally {
                    if(!handedOff) {
                        pending = null
                        withContext(NonCancellable + Dispatchers.IO) {
                            staged?.let { files.finish(it.id, null) }
                            shared?.delete()
                        }
                    }
                    working = false
                }
            }
        }
    }
}
