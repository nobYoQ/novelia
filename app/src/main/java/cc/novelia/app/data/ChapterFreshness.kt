package cc.novelia.app.data

import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import java.io.File

private val chapterFreshnessLock = Any()
private fun freshnessFile(store: LocalStore) = AtomicFile(File(store.context.filesDir, "chapter-freshness.json"))
private fun readFreshness(store: LocalStore): Map<String, Long> = runCatching {
    appJson.decodeFromString<Map<String, Long>>(freshnessFile(store).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
}.getOrDefault(emptyMap())

/** Cache file mtimes are LRU access times; they cannot tell when a translation was fetched. */
fun chapterFreshness(store: LocalStore, ref: BookRef, id: String): Long = synchronized(chapterFreshnessLock) {
    if(!store.chapterFile(ref, id).isFile) 0L else readFreshness(store)[store.chapterFile(ref, id).name] ?: 0L
}

/** Call in the same cache-generation critical section as the successful chapter write. */
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
