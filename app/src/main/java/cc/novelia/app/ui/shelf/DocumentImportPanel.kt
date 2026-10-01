@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.files.*
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    var open by rememberSaveable { mutableStateOf(false) }
    if (state.items.isEmpty()) return
    val reducedMotion = appReducedMotion()
    val counts = listOf(ImportStatus.Success, ImportStatus.Duplicate, ImportStatus.Failed, ImportStatus.Pending)
        .joinToString(" · ") { status -> "${status.label} ${state.items.count { it.status == status }}" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        TextButton(onClick = { open = true }) {
            Text("${if (state.running) "正在导入" else "导入清单"} ${state.completed}/${state.items.size} · 查看结果")
        }
        if (state.running) {
            state.items.firstOrNull { it.status == ImportStatus.Running }?.let { Text("${it.name} · ${it.detail}", maxLines = 2, style = MaterialTheme.typography.bodySmall) }
            LinearProgressIndicator(progress = { state.completed.toFloat() / state.items.size }, modifier = Modifier.fillMaxWidth())
        } else Text(counts, style = MaterialTheme.typography.bodySmall)
    }
    if (open) AppSheet(onDismissRequest = { open = false }) {
        Text("批量导入结果", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Text(counts, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (state.running) TextButton(onClick = model::pause) { Text("暂停导入") }
            else {
                TextButton(onClick = model::retryFailed, enabled = state.items.any { it.status == ImportStatus.Failed }) { Text("仅重试失败项") }
                if (state.items.any { it.status == ImportStatus.Pending }) TextButton(onClick = model::resume) { Text("继续尚未处理项") }
            }
        }
        AppLazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            itemsIndexed(state.items, key = { index, _ -> index }) { index, item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. ${item.name}", style = MaterialTheme.typography.titleSmall)
                    Text("${item.status.label}${if (item.detail.isBlank()) "" else " · ${item.detail}"}", color = if (item.status == ImportStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.status == ImportStatus.Running && !reducedMotion) LinearProgressIndicator(Modifier.fillMaxWidth())
                    item.bookKey?.let { key -> TextButton(onClick = { open = false; onRead(BookRef.fromKey(key)) }) { Text("开始阅读") } }
                }
                HorizontalDivider()
            }
        }
    }
}
