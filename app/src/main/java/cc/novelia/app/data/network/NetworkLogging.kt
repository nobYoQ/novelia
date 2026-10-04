package cc.novelia.app.data.network

import cc.novelia.app.data.storage.appJson
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

internal data class DiagnosticRequestTag(val run: String, val route: String)

/** Disk work never runs on an OkHttp/Go callback or UI thread; overload cannot break a request. */
internal class NetworkRecorder(private val store: NetworkLogStore, private val enabled: () -> Boolean) {
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(512), { task ->
        Thread(task, "novelia-network-log").apply { isDaemon = true }
    })
    private val generation = AtomicLong()
    private val lost = AtomicLong()
    private val traces = Collections.synchronizedMap(WeakHashMap<Call, NetworkRequestTrace>())

    private fun write(epoch: Long = generation.get(), action: () -> Unit) {
        try { writer.execute { if (generation.get() == epoch) try { action() } catch (_: Exception) { lost.incrementAndGet() } } }
        catch (_: Exception) { lost.incrementAndGet() }
    }

    fun system(stage: String, outcome: String, environment: String? = null) = write {
        store.append(NetworkLogEvent(stage = stage, outcome = outcome, environment = environment?.let { appJson.decodeFromString<Map<String, String>>(it) }))
    }
    fun saveReport(report: String) = write { store.saveReport(report) }

    fun attach(builder: OkHttpClient.Builder, echEnabled: () -> Boolean): OkHttpClient.Builder = builder
        .eventListenerFactory { call ->
            val request = call.request()
            val probe = request.tag(DiagnosticRequestTag::class.java)
            if (probe == null && !enabled()) EventListener.NONE else {
                val epoch = generation.get()
                val trace = NetworkRequestTrace(request, probe?.route ?: if(request.tag(SourceSelection::class.java)?.source == BookSource.XKVI) "mirror"
                    else if (echEnabled() && request.url.host in echHosts) "ECH" else "direct") {
                    event -> write(epoch) { store.append(event) }
                }
                traces[call] = trace
                NetworkEvents(trace)
            }
        }.addInterceptor { chain ->
            val trace = traces[chain.call()] ?: return@addInterceptor chain.proceed(chain.request())
            trace.event("call", "start", connect = chain.connectTimeoutMillis(), read = chain.readTimeoutMillis(), write = chain.writeTimeoutMillis())
            try {
                val response = chain.proceed(chain.request().newBuilder().tag(NetworkRequestTrace::class.java, trace).build())
                trace.event("response_headers", "ok", status = response.code, protocol = response.protocol.toString())
                val body = response.body
                if (body == null) { trace.finish("complete", 0); response }
                else response.newBuilder().body(object : ResponseBody() {
                    private var total = 0L
                    private val source = object : ForwardingSource(body.source()) {
                        override fun read(sink: Buffer, byteCount: Long): Long = try {
                            super.read(sink, byteCount).also { count ->
                                if (count < 0) trace.finish("complete", total) else total += count
                            }
                        } catch (error: IOException) { trace.finish("failed", total, networkFailure(error, chain.call().isCanceled())); throw error }
                        override fun close() {
                            try { super.close() }
                            catch (error: IOException) { trace.finish("failed", total, networkFailure(error, chain.call().isCanceled())); throw error }
                            finally { trace.finish(if (body.contentLength() >= 0 && total == body.contentLength()) "complete" else "closed", total) }
                        }
                    }.buffer()
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = source
                }).build()
            } catch (error: IOException) { trace.finish("failed", 0, networkFailure(error, chain.call().isCanceled())); throw error }
        }

    suspend fun export(environment: String): ByteArray = withContext(Dispatchers.IO) {
        // The barrier includes all records queued before the user pressed Export.
        val snapshot = writer.submit<Map<String, ByteArray>> { store.snapshot() }.get(10, TimeUnit.SECONDS)
        networkLogArchive(snapshot, environment, lost.get())
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        generation.incrementAndGet() // In-flight calls may finish, but cannot restore pre-clear records.
        writer.submit { store.clear(); lost.set(0) }.get(10, TimeUnit.SECONDS)
    }
}

internal class NetworkRequestTrace(request: Request, private val route: String, private val emit: (NetworkLogEvent) -> Unit) {
    private val started = System.nanoTime()
    private val finished = AtomicBoolean()
    private val base = NetworkLogEvent(
        id = UUID.randomUUID().toString(), scope = if (request.tag(DiagnosticRequestTag::class.java) == null) "business" else "diagnostic",
        run = request.tag(DiagnosticRequestTag::class.java)?.run,
        route = route, host = request.url.host.takeIf { it in echHosts } ?: "external",
        target = networkTarget(request), method = request.method.takeIf { it in setOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS") } ?: "OTHER",
        stage = "", outcome = ""
    )
    private val exchanges = AtomicInteger()
    private val nativeEvents = mutableMapOf<Int, Int>()
    fun nextNativeExchange() = exchanges.incrementAndGet()

    fun event(stage: String, outcome: String, reason: String? = null, status: Int? = null, bytes: Long? = null,
        protocol: String? = null, family: String? = null, connect: Int? = null, read: Int? = null, write: Int? = null,
        actualRoute: String = route, serverIp: String? = null, proxy: String? = null) {
        emit(base.copy(timeMs = System.currentTimeMillis(), stage = stage, outcome = outcome,
            route = actualRoute,
            elapsedMs = (System.nanoTime() - started) / 1_000_000, reason = reason, status = status, bytes = bytes,
            protocol = protocol, family = family, connectTimeoutMs = connect, readTimeoutMs = read, writeTimeoutMs = write,
            serverIp = serverIp, proxy = proxy))
    }

    fun finish(outcome: String, bytes: Long, reason: String? = null) {
        if (finished.compareAndSet(false, true)) event("call", outcome, reason = reason, bytes = bytes)
    }

    @Synchronized fun native(exchange: Int, json: String) {
        // The bridge produces fixed metadata only. No Go exception text crosses this boundary.
        runCatching {
            val events = appJson.decodeFromString<List<NativeDiagnosticEvent>>(json)
            events.drop(nativeEvents[exchange] ?: 0).forEach { event ->
                emit(base.copy(timeMs = System.currentTimeMillis(), stage = "native.${event.stage}", outcome = event.outcome,
                    route = "ECH", exchange = exchange,
                    elapsedMs = event.atMillis, durationMs = event.durationMs, reason = event.reason,
                    protocol = event.protocol, resolver = event.resolver, family = event.family, attempt = event.attempt, serverIp = event.serverIp))
            }
            nativeEvents[exchange] = events.size
        }
    }
}

@Serializable private data class NativeDiagnosticEvent(
    val stage: String, val outcome: String, val atMillis: Long, val durationMs: Long? = null,
    val reason: String? = null, val resolver: String? = null, val family: String? = null,
    val attempt: Int? = null, val protocol: String? = null, val serverIp: String? = null
)

/** Store categories, never IDs, filenames, search terms, redirect URLs or query parameters. */
internal fun networkTarget(request: Request): String {
    if (request.url.host !in echHosts && request.url.host != "book.xkvi.top") return "external"
    val path = request.url.pathSegments
    return when {
        path == listOf("cdn-cgi", "trace") -> "connectivity_trace"
        request.url.host == "auth.novelia.cc" || path.take(3) == listOf("api", "v1", "auth") -> "authentication"
        request.url.host == "forum.novelia.cc" -> "forum_api"
        path.firstOrNull() == "files-temp" -> "download_file"
        path.getOrNull(1) == "novel" -> when { "file" in path -> "novel_download"; "chapter" in path -> "chapter"; path.size == 2 -> "novel_list"; else -> "novel_api" }
        path.getOrNull(1) == "wenku" -> if ("file" in path) "wenku_download" else "wenku_api"
        else -> "other"
    }
}

internal fun networkFailure(error: Throwable, cancelled: Boolean = false): String = when {
    error is SocketTimeoutException || error is InterruptedIOException -> "timeout"
    cancelled -> "cancelled"
    error is UnknownHostException -> "dns"
    error.hasCertificateCause() -> "certificate"
    error is SSLHandshakeException -> "tls"
    error is LinkageError -> "native_library"
    error.message.orEmpty().contains("reset", ignoreCase = true) -> "reset"
    error.message.orEmpty().contains("unreachable", ignoreCase = true) || error.message.orEmpty().contains("no route", ignoreCase = true) -> "unreachable"
    error is ConnectException -> "connect"
    error is EchIOException -> "ech" // Native events contain the precise stage and fixed failure reason.
    error is IOException -> "io"
    else -> "internal"
}

private fun Throwable.hasCertificateCause(): Boolean {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is SSLPeerUnverifiedException || current is CertificateException || current is CertPathValidatorException) return true
        current = current.cause
    }
    return false
}

private class NetworkEvents(private val trace: NetworkRequestTrace) : EventListener() {
    private fun event(stage: String, outcome: String, reason: String? = null, protocol: String? = null, family: String? = null, bytes: Long? = null) =
        trace.event(stage, outcome, reason = reason, protocol = protocol, family = family, bytes = bytes, actualRoute = "direct")
    override fun dnsStart(call: Call, domainName: String) = event("dns", "start")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        event("dns", "ok", family = inetAddressList.map { if (it.address.size == 4) "IPv4" else "IPv6" }.distinct().joinToString("+"))
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        trace.event("tcp", "start", actualRoute = "direct", proxy = proxy.type().name,
            family = when (inetSocketAddress.address?.address?.size) { 4 -> "IPv4"; 16 -> "IPv6"; else -> "unknown" },
            serverIp = if (proxy.type() == Proxy.Type.DIRECT && call.request().url.host in echHosts) diagnosticServerAddress(inetSocketAddress.address) else null)
    override fun secureConnectStart(call: Call) = event("tls", "start")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = event("tls", "ok", protocol = handshake?.tlsVersion?.javaName)
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = event("tcp", "ok", protocol = protocol?.toString())
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = event("connect", "failed", reason = networkFailure(ioe, call.isCanceled()))
    override fun connectionAcquired(call: Call, connection: Connection) = event("connection", "acquired", protocol = connection.protocol().toString())
    override fun requestHeadersEnd(call: Call, request: Request) = event("request_sent", "headers")
    override fun requestBodyEnd(call: Call, byteCount: Long) = event("request_sent", "body", bytes = byteCount)
    override fun responseHeadersStart(call: Call) = event("first_byte", "ok")
    override fun callFailed(call: Call, ioe: IOException) = trace.event("transport", "failed", reason = networkFailure(ioe, call.isCanceled()))
    // ECH returns an application-interceptor response: OkHttp callEnd precedes reading its body.
    // Only the outer body wrapper may report complete/closed for this reason.
}

internal fun diagnosticServerAddress(address: InetAddress?): String = when {
    address == null -> "unknown"
    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress -> "non_public"
    address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc -> "non_public"
    else -> address.hostAddress.orEmpty().substringBefore('%')
}
