package cc.novelia.app.data.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import kotlin.random.Random

internal const val FOREGROUND_REQUEST_MILLIS = 30_000L
internal object ManagedConnectAttempts

internal fun Throwable?.isRecoverableNetworkFailure(): Boolean = this is IOException &&
    this !is ApiException && this !is SSLException &&
    // OkHttp uses ProtocolException for a truncated fixed-length HTTP/1 body.
    (this !is ProtocolException || message == "unexpected end of stream") &&
    (this !is EchIOException || retryableNetworkFailure) &&
    (this !is InterruptedIOException || this is SocketTimeoutException)

private data class BudgetResult<T>(val value: T)

/** The budget includes dispatch, auth refresh, backoff, connectivity wait and full body consumption. */
internal suspend fun <T> withRequestBudget(millis: Long, block: suspend () -> T): T {
    val result = withTimeoutOrNull(millis) { BudgetResult(block()) }
        ?: throw SocketTimeoutException("请求超时，请稍后重试")
    return result.value
}

internal fun OkHttpClient.foregroundBudget(): Long = callTimeoutMillis.toLong().takeIf { it > 0 }
    ?.coerceAtMost(FOREGROUND_REQUEST_MILLIS) ?: FOREGROUND_REQUEST_MILLIS

internal fun OkHttpClient.networkConnections() = interceptors.filterIsInstance<NetworkGenerationInterceptor>()
    .firstOrNull()?.connections

/** One outer replacement; managed ECH attempts use one connect attempt each, rather than nested retries. */
internal suspend fun <T> recoverRead(connections: NetworkConnections?, read: suspend () -> T): T {
    val generation = connections?.generation()
    return try { read() } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        if (!error.isRecoverableNetworkFailure()) throw error
        waitForNetworkRecovery(connections, generation, error)
        currentCoroutineContext().ensureActive()
        read()
    }
}

internal suspend fun waitForNetworkRecovery(connections: NetworkConnections?, generation: Long?, error: IOException) {
    connections?.recovering(true)
    try {
        when {
            error is NetworkChangedException -> if (!error.connections.awaitReplacement(error.generation)) throw error
            connections != null && generation != null && connections.changedSince(generation) ->
                if (!connections.awaitReplacement(generation)) throw error
            else -> {
                delay(Random.nextLong(300, 601))
                if (connections != null && !connections.awaitReady()) throw error
            }
        }
    } finally { connections?.recovering(false) }
}
