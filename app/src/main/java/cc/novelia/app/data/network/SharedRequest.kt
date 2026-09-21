package cc.novelia.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 按键合并正在进行的请求：同键订阅者等待同一个 Deferred，最后一个订阅者离开时取消 IO。
 * 独立作用域让某个页面的取消不会直接取消其他页面仍需要的请求；finally 负责引用计数释放。
 * 这里不是结果缓存，所有订阅结束后会移除条目。键必须包含决定请求身份的全部上下文。
 */
internal class SharedRequest<K, V> {
    private class Entry<V>(val task: Deferred<V>, var subscribers: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = mutableMapOf<K, Entry<V>>()

    /** 主动使一组请求失效，例如清理缓存时取消旧代次；现有等待者会收到取消。 */
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
                    // 旧请求结束时，同键可能已创建替代请求；只能删除自己对应的条目。
                    if (entries[key] === entry) entries.remove(key)
                    entry.task.cancel()
                }
            }
        }
    }
}
