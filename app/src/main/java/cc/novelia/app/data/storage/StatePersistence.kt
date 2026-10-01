package cc.novelia.app.data.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 将频繁状态修改合并为串行磁盘写入。Channel 只传递唤醒信号，最新快照保存在 pending 中，
 * 因而短时间内的连续翻页只需保存最后一个状态。调用方必须提交不会继续被修改的快照。
 * pendingLock 只保护快照引用；writeLock 保护整个挂起写入，避免后台保存与显式 flush 并行。
 * 写入失败保留快照并重试，同时通过 [error] 向界面暴露可恢复的保存错误。
 */
internal class StatePersistence<T>(
    scope: CoroutineScope,
    private val coalesceMillis: Long = 100,
    private val retryMillis: Long = 1_000,
    private val write: suspend (T) -> Unit
) {
    private class Pending<T>(val value: T)

    private val pendingLock = Any()
    private val writeLock = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var pending: Pending<T>? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                delay(coalesceMillis)
                try {
                    flush()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 短暂磁盘故障后保留最新快照，并让写入协程继续运行。
                    delay(retryMillis)
                    requests.trySend(Unit)
                }
            }
        }
    }

    fun submit(value: T) {
        synchronized(pendingLock) { pending = Pending(value) }
        requests.trySend(Unit)
    }

    /**
     * 保存取得写锁时的待写快照，失败向调用方传播。写入期间仍允许 submit 更新 pending；
     * 因此这不是冻结所有后续修改的全局屏障，新提交的快照会留给下一轮保存。
     */
    suspend fun flush() = writeLock.withLock {
        val snapshot = synchronized(pendingLock) { pending } ?: return@withLock
        try {
            write(snapshot.value)
            // 比较包装对象身份，不能按值相等清空，否则可能丢掉写入期间新提交的快照。
            synchronized(pendingLock) { if (pending === snapshot) pending = null }
            mutableError.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableError.value = "本地数据暂未保存，正在重试，请稍后再退出应用。"
            throw error
        }
    }
}
