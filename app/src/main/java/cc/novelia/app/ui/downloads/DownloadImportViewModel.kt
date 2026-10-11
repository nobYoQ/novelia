package cc.novelia.app.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.files.importing.ImportItem
import cc.novelia.app.files.importing.importDownloadedDocument
import cc.novelia.app.files.importing.runDocumentImportBatch
import cc.novelia.app.ui.components.base.friendlyMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class DownloadImportState(val items: List<ImportItem> = emptyList(), val running: Boolean = false)

/** 批次随下载页面保留，旋转时继续处理，不持有页面控制器或 Activity。 */
internal class DownloadImportViewModel(private val app: NoveliaApplication) : ViewModel() {
    private val mutable = MutableStateFlow(DownloadImportState())
    val state: StateFlow<DownloadImportState> = mutable
    private var task: Job? = null

    fun start(entries: List<DownloadEntry>) {
        if(state.value.running) return
        val targets = entries.filter { it.status == "已完成" }.distinctBy { it.id }
        if(targets.isEmpty()) return
        mutable.value = DownloadImportState(targets.map { ImportItem(it.id, it.title) })
        run(false)
    }

    fun retryFailed() = run(true)
    fun resume() = run(false)
    fun pause() { task?.cancel() }

    private fun run(failedOnly: Boolean) {
        if(state.value.running || state.value.items.isEmpty()) return
        mutable.value = state.value.copy(running = true)
        task = viewModelScope.launch {
            try {
                val result = runDocumentImportBatch(state.value.items, failedOnly,
                    onChange = { mutable.value = DownloadImportState(it, true) }, errorMessage = { it.friendlyMessage() }) { item, progress ->
                    val entry = app.store.state.value.downloads.firstOrNull { it.id == item.source }
                        ?: error("下载任务已不存在")
                    importDownloadedDocument(app, entry, onProgress = progress)
                }
                mutable.value = DownloadImportState(result)
            } finally { mutable.value = mutable.value.copy(running = false) }
        }
    }
}

@Composable internal fun rememberDownloadImporter(app: NoveliaApplication): DownloadImportViewModel {
    val factory = remember(app) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DownloadImportViewModel(app) as T
        }
    }
    return viewModel(key = "download-document-import", factory = factory)
}
