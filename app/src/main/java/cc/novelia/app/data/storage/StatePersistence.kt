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

/** A single writer coalesces bursts without holding the caller's state lock during disk IO. */
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
                    // Keep the latest snapshot and the writer alive after a transient disk failure.
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

    /** Returns only after the current snapshot is durable; write failures are propagated. */
    suspend fun flush() = writeLock.withLock {
        val snapshot = synchronized(pendingLock) { pending } ?: return@withLock
        try {
            write(snapshot.value)
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
