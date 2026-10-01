package cc.novelia.app.data.chapters

import android.util.AtomicFile
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.storage.appJson
import java.io.File
import kotlinx.serialization.encodeToString

private val chapterFreshnessLock = Any()
private fun freshnessFile(store: LocalStore) = AtomicFile(File(store.context.filesDir, "chapter-freshness.json"))
private fun readFreshness(store: LocalStore): Map<String, Long> = runCatching {
    appJson.decodeFromString<Map<String, Long>>(freshnessFile(store).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
}.getOrDefault(emptyMap())

/**
 * 获取章节实际下载时间；缓存文件修改时间用于 LRU 最近访问，不能代表译文新鲜度。
 * 正文不存在或旧缓存缺少时间记录时返回 0，允许界面保守提示可能需要刷新。
 */
fun chapterFreshness(store: LocalStore, ref: BookRef, id: String): Long = synchronized(chapterFreshnessLock) {
    if(!store.chapterFile(ref, id).isFile) 0L else readFreshness(store)[store.chapterFile(ref, id).name] ?: 0L
}

/** 必须与章节成功写入处于同一缓存代次临界区，保持正文和获取时间一致。 */
fun recordChapterFreshness(store: LocalStore, ref: BookRef, id: String, fetchedAt: Long = System.currentTimeMillis()) = synchronized(chapterFreshnessLock) {
    val file = store.chapterFile(ref, id)
    val alive = store.cacheDir.listFiles()?.asSequence()?.filter { it.isFile && it.extension == "json" }?.map { it.name }?.toSet().orEmpty()
    val next = (readFreshness(store).filterKeys { it in alive } + if(file.name in alive) mapOf(file.name to fetchedAt) else emptyMap())
        .entries.sortedByDescending { it.value }.take(4096).associate { it.key to it.value }
    val atomic = freshnessFile(store)
    val stream = atomic.startWrite()
    try { stream.write(appJson.encodeToString(next).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
    catch(e: Exception) { atomic.failWrite(stream); throw e }
}

fun clearChapterFreshness(store: LocalStore) = synchronized(chapterFreshnessLock) { freshnessFile(store).delete() }
