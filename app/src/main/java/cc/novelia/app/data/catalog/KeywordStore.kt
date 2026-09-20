package cc.novelia.app.data.catalog

import android.content.Context
import android.util.AtomicFile
import cc.novelia.app.data.storage.appJson
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Browser-derived vocabulary only; it never starts a network request. */
class KeywordStore(context: Context) {
    companion object { const val FILE_NAME = "keyword-catalog.json" }
    private val file = File(context.filesDir, FILE_NAME)
    private val atomic = AtomicFile(file)
    private val lock = Any()
    private val writeLock = Any()
    private data class Snapshot(val revision: Long, val entries: List<KeywordEntry>)
    private var revision = 0L
    private var failedRead = false
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<List<KeywordEntry>> = mutable.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = mutableError.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for(ignored in writes) {
                delay(200)
                try { flush() }
                catch(error: kotlinx.coroutines.CancellationException) { throw error }
                catch(_: Exception) {
                    delay(1_000)
                    writes.trySend(Unit)
                }
            }
        }
    }

    fun observe(originals: Collection<String>) = change { KeywordCatalog.observe(it, originals) }
    fun markUsed(originals: Collection<String>) {
        val now = System.currentTimeMillis()
        change { entries -> KeywordCatalog.markUsed(entries, originals, now) }
    }
    fun setTranslation(original: String, translation: String) {
        val normalized = original.trim()
        if(normalized.isBlank()) return
        change { entries -> KeywordCatalog.translate(entries, normalized, translation.trim()) }
    }
    fun exportSnapshot(): List<KeywordEntry> = state.value.toList()
    fun mergeSnapshot(entries: List<KeywordEntry>) = change { KeywordCatalog.merge(it, entries) }
    fun reload() = synchronized(writeLock) {
        val before = synchronized(lock) { mutable.value }
        val loaded = read()
        if(failedRead) {
            mutableError.value = "标签词库读取失败，已保留当前标签；再次保存时会保留损坏原文件。"
            return@synchronized
        }
        val prior = before.associateBy { it.original }
        // Disk IO can overlap reader edits. Only those concurrent changes override the reload.
        change { current ->
            if(current === before) loaded
            else KeywordCatalog.withDefaults(current.filter { prior[it.original] != it } + loaded)
        }
        mutableError.value = null
    }

    /** Call from an IO dispatcher when a backup or lifecycle event requires a durable snapshot. */
    fun flush() = synchronized(writeLock) {
        // Capture after taking the write lock: an older waiting flush cannot overwrite a newer one.
        // Lists and entries are immutable; subsequent changes enqueue their own persistence request.
        val snapshot = synchronized(lock) { mutable.value }
        try {
            val encoded = appJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            if(failedRead && file.exists()) {
                // Preserve unreadable input before any subsequent browsing can replace it.
                file.copyTo(File(file.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}"))
                failedRead = false
            }
            val stream = atomic.startWrite()
            try {
                stream.write(encoded)
                atomic.finishWrite(stream)
            } catch(error: Exception) { atomic.failWrite(stream); throw error }
            mutableError.value = null
        } catch(error: Exception) {
            mutableError.value = "标签词库和翻译暂未保存，正在重试，请稍后再退出应用。"
            throw error
        }
    }

    private fun change(transform: (List<KeywordEntry>) -> List<KeywordEntry>) {
        while(true) {
            val snapshot = synchronized(lock) { Snapshot(revision, mutable.value) }
            // Catalog ranking/merging also stays outside the state lock used by the UI and flush.
            val next = transform(snapshot.entries)
            val changed = next != snapshot.entries
            val committed = synchronized(lock) {
                if(revision != snapshot.revision) false
                else {
                    if(changed) { mutable.value = next; revision++ }
                    true
                }
            }
            if(committed) {
                if(changed) writes.trySend(Unit)
                return
            }
        }
    }
    private fun read(): List<KeywordEntry> {
        failedRead = false
        if(!file.exists() && !File(file.path + ".bak").exists()) return KeywordCatalog.common
        return try {
            KeywordCatalog.withDefaults(appJson.decodeFromString<List<KeywordEntry>>(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }))
        } catch(_: Exception) { failedRead = true; KeywordCatalog.common }
    }
}
