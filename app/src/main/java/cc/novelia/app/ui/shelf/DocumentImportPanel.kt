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
import cc.novelia.app.data.model.BookMedium
import cc.novelia.app.data.model.shelfMedium
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

internal data class DocumentImportState(val items: List<ImportItem> = emptyList(), val running: Boolean = false, val error: String? = null)

/** ViewModel 保留批次和任务，屏幕旋转不会中断导入或丢失失败项。 */
internal class DocumentImportViewModel(private val store: LocalStore) : ViewModel() {
    private val mutable = MutableStateFlow(DocumentImportState())
    val state: StateFlow<DocumentImportState> = mutable
    private var task: Job? = null
    private var showImportedShelf = false

    private var series = false
    fun start(uris: List<Uri>, asSeries: Boolean = false) {
        if (state.value.running || uris.isEmpty()) return
        series = asSeries
        showImportedShelf = true
        mutable.value = DocumentImportState(uris.map { uri ->
            ImportItem(uri.toString(), uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "导入文件")
        })
        run(false)
    }

    fun retryFailed() = run(true)
    fun resume() = run(false)
    fun pause() { task?.cancel() }

    /** 同一批只自动定位一次；混合批次继续显示完整导入清单。 */
    fun takeImportedMedium(): BookMedium? {
        val batch = state.value
        if (!showImportedShelf || batch.running || batch.items.any { it.status == ImportStatus.Pending || it.status == ImportStatus.Running }) return null
        showImportedShelf = false
        val keys = batch.items.mapNotNull { it.bookKey }.toSet()
        return store.state.value.books.asSequence().filter { it.book.ref.key in keys }
            .map { it.book.shelfMedium }.distinct().singleOrNull()
    }

    // 关闭时清掉批次提示的来源，返回书架或重建页面后也不会再次显示；书籍仍留在书库。
    fun dismissResults() {
        if(!state.value.running) mutable.value = DocumentImportState()
    }

    private fun run(failedOnly: Boolean) {
        if (state.value.running || state.value.items.isEmpty()) return
        mutable.value = state.value.copy(running = true)
        task = viewModelScope.launch {
            var latestItems = state.value.items
            var lastPublishedAt = 0L
            var failure: String? = null
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
                latestItems = named
                store.documentImportBatch {
                    runDocumentImportBatch(named, failedOnly, onChange = { items ->
                        latestItems = items
                        val now = android.os.SystemClock.elapsedRealtime()
                        if(now - lastPublishedAt >= 100) {
                            mutable.value = DocumentImportState(items, true)
                            lastPublishedAt = now
                        }
                    }, errorMessage = { it.friendlyMessage() }) { item, progress ->
                        if (series) cc.novelia.app.files.comic.importComicSeries(store, Uri.parse(item.source), progress = progress)
                        else importDocumentUri(store, Uri.parse(item.source), onProgress = progress)
                    }
                }.also { mutable.value = DocumentImportState(it) }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { failure = error.friendlyMessage() }
            finally {
                // 暂停/失败时也发布最后状态，不能让节流隐藏已经成功或待重试的项。
                mutable.value = DocumentImportState(latestItems, false, failure)
            }
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
    ImportResultsPanel(state.items, state.running, model::pause, model::retryFailed, model::resume, onRead,
        onDismiss = model::dismissResults, error = state.error)
}
