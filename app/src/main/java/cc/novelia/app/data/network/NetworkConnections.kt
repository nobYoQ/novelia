package cc.novelia.app.data.network

import java.io.IOException
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal data class NetworkStatus(val generation: Long = 0, val ready: Boolean = false, val recovering: Int = 0)
internal data class NetworkRequestGeneration(val value: Long, val pool: ConnectionPool? = null)
/** Opt-in for bodyless reads with an owner that can safely discard or validate partial results. */
internal object RecoverableNetworkRead
internal class NetworkChangedException(val connections: NetworkConnections, val generation: Long,
    cause: IOException) : IOException("网络切换导致连接中断，请重试", cause)

/** Owns pool generations, including derived clients, without cancelling healthy old transfers. */
internal class NetworkConnections(private val resetNative: () -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }, private val stallMillis: Long = 3_000) {
    private val status = MutableStateFlow(NetworkStatus())
    val state = status.asStateFlow()
    private var snapshot = NetworkSnapshot()
    private var changing = false
    private var changedAt = 0L
    private val pools = IdentityHashMap<ConnectionPool, ConnectionPool>()
    private val resolvers = IdentityHashMap<Dns, Dns>()
    private val clients = WeakHashMap<OkHttpClient, OkHttpClient>()
    private val reads = mutableSetOf<NetworkReadAttempt>()
    private val watchRequired = MutableStateFlow(false)

    @Synchronized fun pending(value: Boolean) { changing = value }

    @Synchronized fun update(next: NetworkSnapshot) {
        val changed = next.route != snapshot.route
        snapshot = next
        if (changed) rotate(next.ready)
        else status.value = status.value.copy(ready = next.ready)
    }

    @Synchronized fun reset() = rotate()

    @Synchronized fun resetClient(client: OkHttpClient) {
        val previous = pools[client.connectionPool] ?: client.connectionPool
        previous.evictAll()
        pools[client.connectionPool] = ConnectionPool()
        clients.clear()
    }

    private fun rotate(ready: Boolean = status.value.ready) {
        // Capture and reset use the same lock: new calls cannot pick a half-reset generation.
        resetNative()
        pools.entries.forEach { entry ->
            entry.value.evictAll()
            entry.setValue(ConnectionPool())
        }
        resolvers.clear()
        clients.clear()
        changedAt = now()
        status.value = status.value.copy(generation = status.value.generation + 1, ready = ready)
        updateWatch()
    }

    @Synchronized fun newCall(client: OkHttpClient, request: Request): Call {
        val pool = pools.getOrPut(client.connectionPool) { client.connectionPool }
        val managed = clients.getOrPut(client) {
            // A new DNS identity also drops OkHttp's old failed-route decisions; delegates preserve DNS policy.
            val delegate = client.dns
            val dns = resolvers.getOrPut(delegate) {
                object : Dns { override fun lookup(hostname: String) = delegate.lookup(hostname) }
            }
            client.newBuilder().connectionPool(pool).dns(dns).retryOnConnectionFailure(false).build()
        }
        return managed.newCall(request.newBuilder()
            .tag(NetworkRequestGeneration::class.java, NetworkRequestGeneration(status.value.generation, pool)).build())
    }

    @Synchronized fun generation() = status.value.generation
    @Synchronized fun changedSince(generation: Long) = changing || status.value.generation != generation
    @Synchronized internal fun track(call: Call, generation: Long): NetworkReadAttempt =
        NetworkReadAttempt(call, generation, now()).also { reads.add(it); updateWatch() }
    @Synchronized internal fun finished(attempt: NetworkReadAttempt) { reads.remove(attempt); updateWatch() }
    @Synchronized internal fun release(binding: NetworkRequestGeneration) {
        if (binding.value != status.value.generation) binding.pool?.evictAll()
    }
    private fun updateWatch() { watchRequired.value = reads.any { it.generation != status.value.generation } }

    fun watch(scope: CoroutineScope) = scope.launch {
        watchRequired.collectLatest { required ->
            if (required) while (isActive) { delay(500); cancelStalledReads() }
        }
    }

    @Synchronized internal fun cancelStalledReads() {
        val current = status.value.generation
        val time = now()
        reads.filter { it.generation != current && time - maxOf(it.progressAt, changedAt) >= stallMillis }
            .forEach { it.interrupted = true; it.call.cancel() }
    }

    suspend fun awaitReplacement(generation: Long): Boolean = withTimeoutOrNull(5_000) {
        status.first { it.generation > generation && it.ready }
        true
    } ?: false

    suspend fun awaitReady(): Boolean = withTimeoutOrNull(5_000) { status.first { it.ready }; true } ?: false
    @Synchronized fun recovering(start: Boolean) {
        status.value = status.value.copy(recovering = (status.value.recovering + if (start) 1 else -1).coerceAtLeast(0))
    }

    internal fun progress(attempt: NetworkReadAttempt) { attempt.progressAt = now() }
}

internal class NetworkReadAttempt(val call: Call, val generation: Long, @Volatile var progressAt: Long) {
    @Volatile var interrupted = false
}

internal fun OkHttpClient.newNetworkCall(request: Request): Call =
    interceptors.filterIsInstance<NetworkGenerationInterceptor>().firstOrNull()?.connections?.newCall(this, request)
        ?: newCall(request)

/** Records generation on every exchange; detects transport failure through response-body EOF. */
internal class NetworkGenerationInterceptor(val connections: NetworkConnections) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val binding = chain.request().tag(NetworkRequestGeneration::class.java)
            ?: NetworkRequestGeneration(connections.generation())
        val generation = binding.value
        val request = chain.request().newBuilder()
            .tag(NetworkRequestGeneration::class.java, binding).build()
        val recoverable = request.tag(RecoverableNetworkRead::class.java) != null && request.body == null &&
            request.method in setOf("GET", "HEAD")
        val attempt = if (recoverable) connections.track(chain.call(), generation) else null
        fun finish() { if (attempt != null) connections.finished(attempt); connections.release(binding) }
        fun failure(error: IOException): IOException =
            if (recoverable && error.isRecoverableNetworkFailure() &&
                (!chain.call().isCanceled() || attempt?.interrupted == true) && connections.changedSince(generation))
                NetworkChangedException(connections, generation, error) else error
        fun checkInterrupted() {
            if (attempt?.interrupted == true) throw IOException("Network changed")
        }
        try {
            // A request may have waited in a dispatch queue while pools rotated. Never send it on that pool.
            if (generation != connections.generation()) throw IOException("网络已切换，请重试")
            val response = chain.proceed(request)
            if (attempt?.interrupted == true) { response.close(); checkInterrupted() }
            if (attempt != null) connections.progress(attempt)
            val body = response.body ?: return response.also { finish() }
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = try {
                    super.read(sink, byteCount).also {
                        checkInterrupted()
                        if (it == -1L) finish() else if (it > 0 && attempt != null) connections.progress(attempt)
                    }
                } catch (error: IOException) { finish(); throw failure(error) }
                override fun close() { try { super.close() } finally { finish() } }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: IOException) { finish(); throw failure(error) }
        catch (error: Exception) { finish(); throw error }
    }
}
