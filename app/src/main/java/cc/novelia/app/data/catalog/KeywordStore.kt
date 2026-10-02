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

/**
 * 保存用户已浏览内容中的标签及其本地翻译，不主动请求全站标签库。
 * 内存状态即时发布，合并/排序在状态锁外完成，磁盘写入由独立写锁串行化并合并短时间更新。
 * 原文件损坏时先使用常用标签，后续写入前保留损坏副本，避免无声覆盖用户词典。
 */
class KeywordStore(context: Context, private val entryLimit: () -> Int? = { null }) {
    companion object { const val FILE_NAME = "keyword-catalog.json" }
    private val file = File(context.filesDir, FILE_NAME)
    private val atomic = AtomicFile(file)
    private val lock = Any()
    private val writeLock = Any()
    private data class Snapshot(val revision: Long, val library: KeywordLibrary)
    private var revision = 0L
    private var failedRead = false
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<KeywordLibrary> = mutable.asStateFlow()
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

    fun observe(originals: Collection<String>) = change { it.withEntries(KeywordCatalog.observe(it.entries, originals, entryLimit())) }
    fun markUsed(originals: Collection<String>) {
        val now = System.currentTimeMillis()
        change { it.withEntries(KeywordCatalog.markUsed(it.entries, originals, now, entryLimit())) }
    }
    fun setTranslation(original: String, translation: String) {
        val normalized = original.trim()
        if(normalized.isBlank()) return
        change { it.withEntries(KeywordCatalog.translate(it.entries, normalized, translation.trim(), entryLimit())) }
    }
    fun createCategory(name: String) = change { it.createCategory(name.trim()) }
    fun renameCategory(old: String, name: String) = change { it.renameCategory(old, name.trim()) }
    fun deleteCategory(name: String) = change { it.deleteCategory(name) }
    fun editEntry(original: String, translation: String, category: String) = change { it.editEntry(original.trim(), translation.trim(), category, entryLimit()) }
    fun exportLibrary(): KeywordLibrary = state.value
    fun exportSnapshot(): List<KeywordEntry> = state.value.entries
    fun mergeLibrary(library: KeywordLibrary) = change { it.merge(library, entryLimit()) }
    fun mergeSnapshot(entries: List<KeywordEntry>) = mergeLibrary(KeywordLibrary.fromLegacy(entries, addDefaults = false))
    /**
     * 写锁保护重载与落盘顺序，磁盘读取期间仍允许内存编辑；仅保留此期间发生的并发修改。
     * 读取失败继续发布原内存状态，并保留损坏文件供下次保存前备份。
     */
    fun reload() = synchronized(writeLock) {
        val before = synchronized(lock) { mutable.value }
        val loaded = read()
        if(failedRead) {
            mutableError.value = "标签词库读取失败，已保留当前标签；再次保存时会保留损坏原文件。"
            return@synchronized
        }
        val prior = before.entries.associateBy { it.original }
        // 磁盘 IO 期间用户仍可编辑，仅让这段时间内的并发改动覆盖重载结果。
        change { current ->
            if(current === before) loaded
            else {
                val edited = current.entries.filter { prior[it.original] != it }
                val base = if(current.categories != before.categories) current else loaded
                base.withEntries((edited + loaded.entries).distinctBy { it.original })
            }
        }
        mutableError.value = null
    }

    /** 备份或生命周期事件需要可靠落盘时，应从 IO 调度器调用。 */
    fun flush() = synchronized(writeLock) {
        // 获得写锁后再获取快照，避免较早等待的 flush 覆盖较新的写入。
        // 列表和条目均不可变；后续修改会各自提交持久化请求。
        val snapshot = synchronized(lock) { mutable.value }
        try {
            val encoded = appJson.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
            if(failedRead && file.exists()) {
                // 先保留无法读取的原始文件，避免后续浏览触发写入将其覆盖。
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

    // 乐观变换：锁外计算后比较版本，若其间有编辑则基于新快照重算，避免覆盖并发修改。
    // transform 可能执行多次，必须保持为无外部副作用的列表变换。
    private fun change(transform: (KeywordLibrary) -> KeywordLibrary) {
        while(true) {
            val snapshot = synchronized(lock) { Snapshot(revision, mutable.value) }
            // 词条排序和合并也放在界面及 flush 使用的状态锁之外。
            val next = transform(snapshot.library)
            val changed = next != snapshot.library
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
    private fun read(): KeywordLibrary {
        failedRead = false
        if(!file.exists() && !File(file.path + ".bak").exists()) return KeywordLibrary.defaults()
        return try {
            val text = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val loaded = KeywordLibraryFormat.decode(text)
            if(text.trimStart().startsWith("[")) KeywordLibrary.fromLegacy(loaded.entries) else loaded
        } catch(_: Exception) { failedRead = true; KeywordLibrary.defaults() }
    }
}
