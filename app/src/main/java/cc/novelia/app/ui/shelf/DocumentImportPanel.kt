@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.files.*
import cc.novelia.app.ui.components.documents.ImportResultsPanel
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import cc.novelia.app.files.importing.ImportItem
import cc.novelia.app.files.importing.ImportStatus
import cc.novelia.app.files.importing.importDocumentUri
import cc.novelia.app.files.importing.runDocumentImportBatch

internal data class DocumentImportState(val items: List<ImportItem> = emptyList(), val running: Boolean = false) {
    val completed get() = items.count { it.status in setOf(ImportStatus.Success, ImportStatus.Duplicate, ImportStatus.Failed) }
    val bookKeys get() = items.mapNotNull { it.bookKey }.distinct()
}

/** ViewModel 保留批次和任务，屏幕旋转不会中断导入或丢失失败项。 */
internal class DocumentImportViewModel(private val store: LocalStore) : ViewModel() {
    private val mutable = MutableStateFlow(DocumentImportState())
    val state: StateFlow<DocumentImportState> = mutable
    private var task: Job? = null

    fun start(uris: List<Uri>) {
        if (state.value.running || uris.isEmpty()) return
        mutable.value = DocumentImportState(uris.map { uri ->
            ImportItem(uri.toString(), uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "导入文件")
        })
        run(false)
    }

    fun retryFailed() = run(true)
    fun resume() = run(false)
    fun pause() { task?.cancel() }

    private fun run(failedOnly: Boolean) {
        if (state.value.running) return
        mutable.value = state.value.copy(running = true)
        task = viewModelScope.launch {
            try {
                // 显示名查询也可能失败，不能影响文件流读取或其余批次。
                val named = withContext(Dispatchers.IO) {
                    state.value.items.map { item ->
                        currentCoroutineContext().ensureActive()
                        val targetStatus = if (failedOnly) ImportStatus.Failed else ImportStatus.Pending
                        if (item.status != targetStatus) return@map item
                        val name = try {
                            store.context.contentResolver.query(Uri.parse(item.source), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        item.copy(name = name?.takeIf { it.isNotBlank() } ?: item.name)
                    }
                }
                runDocumentImportBatch(named, failedOnly, onChange = { mutable.value = DocumentImportState(it, true) }, errorMessage = { it.friendlyMessage() }) { item, progress ->
                    importDocumentUri(store, Uri.parse(item.source), onProgress = progress)
                }.also { mutable.value = DocumentImportState(it) }
            } finally { mutable.value = mutable.value.copy(running = false) }
        }
    }
}

@Composable internal fun rememberDocumentImporter(c: AppController): DocumentImportViewModel {
    val factory = remember(c.store) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DocumentImportViewModel(c.store) as T
        }
    }
    return viewModel(key = "shelf-document-import", factory = factory)
}

@Composable internal fun DocumentImportPanel(model: DocumentImportViewModel, onRead: (BookRef) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    ImportResultsPanel(state.items, state.running, model::pause, model::retryFailed, model::resume, onRead)
}
