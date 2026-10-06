package cc.novelia.app.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 只保留去重后的原文和一个唤醒信号；网络回调不为每本书创建整库变换任务。 */
internal class KeywordObservationQueue(
    scope: CoroutineScope,
    private val known: (String) -> Boolean,
    private val observe: (Collection<String>) -> Unit,
    private val failed: (Exception) -> Unit,
) {
    private val pendingLock = Any()
    private val drainLock = Any()
    private val pending = linkedSetOf<String>()
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for(ignored in signals) {
                delay(100)
                try { flush() }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) {
                    failed(error)
                    delay(1_000)
                    signals.trySend(Unit)
                }
            }
        }
    }

    fun enqueue(originals: Collection<String>) {
        val added = synchronized(pendingLock) {
            var changed = false
            originals.forEach { value ->
                val tag = value.trim()
                if(tag.isNotBlank() && tag.length <= KeywordCatalog.MAX_TEXT_LENGTH && !known(tag)) {
                    if(pending.add(tag)) changed = true
                }
            }
            changed
        }
        if(added) signals.trySend(Unit)
    }

    /** 显式保存也会排空已提交的观察，不能让退出/备份丢掉正在等待合并的标签。 */
    fun flush() = synchronized(drainLock) {
        val batch = synchronized(pendingLock) { pending.toList().also { pending.clear() } }
        if(batch.isEmpty()) return@synchronized
        try { observe(batch) }
        catch(error: Exception) {
            synchronized(pendingLock) { pending.addAll(batch) }
            throw error
        }
    }
}
