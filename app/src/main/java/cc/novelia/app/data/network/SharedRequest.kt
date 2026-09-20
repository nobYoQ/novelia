package cc.novelia.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Subscribers share one request; leaving the last subscriber cancels the underlying IO. */
internal class SharedRequest<K, V> {
    private class Entry<V>(val task: Deferred<V>, var subscribers: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = mutableMapOf<K, Entry<V>>()

    fun cancelWhere(predicate: (K) -> Boolean) = synchronized(entries) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) { iterator.remove(); entry.value.task.cancel() }
        }
    }

    suspend fun await(key: K, load: suspend () -> V): V {
        val entry = synchronized(entries) {
            val current = entries[key]?.takeUnless { it.task.isCancelled }
                ?: Entry(scope.async(start = CoroutineStart.LAZY) { load() }).also { entries[key] = it }
            current.subscribers++
            current
        }
        try { return entry.task.await() }
        finally {
            synchronized(entries) {
                entry.subscribers--
                if (entry.subscribers == 0) {
                    if (entries[key] === entry) entries.remove(key)
                    entry.task.cancel()
                }
            }
        }
    }
}
