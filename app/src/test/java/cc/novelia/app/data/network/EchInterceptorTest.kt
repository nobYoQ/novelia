package cc.novelia.app.data.network

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class EchInterceptorTest {
    @Test fun fixedEchFailureMessageSurvivesTheAdapter() {
        val failure = EchIOException("ECH 连接超时，请在设置中运行连接诊断")
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute(): EchReply = throw failure
                override fun read(maxBytes: Long) = ByteArray(0)
                override fun cancel() {}
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true })).build()
        try { client.newCall(Request.Builder().url("https://forum.novelia.cc/api/v1/category/").build()).execute(); fail() }
        catch (error: IOException) { assertSame(failure, error) }
    }

    private fun reply(code: Int = 200, headers: Headers = Headers.Builder().build()) = EchReply(code, Protocol.HTTP_2, headers, -1)
    private fun redirect(url: String, code: Int, location: String, body: RequestBody? = null): Response {
        val request = Request.Builder().url(url).header("Authorization", "test-value").header("Cookie", "test-cookie")
            .apply { if (body != null) post(body) }.build()
        return Response.Builder().request(request).protocol(Protocol.HTTP_2).code(code).message("")
            .header("Location", location).body(ByteArray(0).toResponseBody()).build()
    }

    @Test fun redirectDropsCredentialsAcrossOriginsAndRejectsDowngrade() {
        val cross = redirectRequest(redirect("https://n.novelia.cc/file", 302, "https://forum.novelia.cc/file"))!!
        assertNull(cross.header("Authorization")); assertNull(cross.header("Cookie"))
        val same = redirectRequest(redirect("https://n.novelia.cc/file", 302, "/next"))!!
        assertEquals("test-value", same.header("Authorization"))
        assertNull(redirectRequest(redirect("https://n.novelia.cc/file", 302, "http://n.novelia.cc/file")))
    }

    @Test fun redirectDoesNotReplayOneShotBody() {
        val body = object : RequestBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) { sink.writeUtf8("example") }
        }
        assertNull(redirectRequest(redirect("https://n.novelia.cc/", 307, "/next", body)))
        assertEquals("GET", redirectRequest(redirect("https://n.novelia.cc/", 303, "/next", body))!!.method)
    }

    @Test fun failureDoesNotFallBackOrReplayPost() {
        var attempts = 0
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                attempts++
                return object : EchExchange {
                    override fun execute(): EchReply = throw IOException("failed")
                    override fun read(maxBytes: Long) = ByteArray(0)
                    override fun cancel() {}
                }
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true }))
            .addInterceptor { error("must not use plaintext transport") }.build()
        try { client.newCall(Request.Builder().url("https://n.novelia.cc/api/").post("example".toRequestBody()).build()).execute(); fail() }
        catch (_: IOException) { assertEquals(1, attempts) }
    }

    @Test fun streamClosesAndNonSuccessStatusAndCookieHeadersSurvive() {
        val cancelled = AtomicBoolean(false)
        val data = Buffer().write(ByteArray(5 * 1024 * 1024) { 42 })
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute() = reply(401, Headers.Builder().add("Set-Cookie", "first=1").add("Set-Cookie", "second=2").build())
                override fun read(maxBytes: Long) = data.readByteArray(minOf(maxBytes, data.size))
                override fun cancel() { cancelled.set(true) }
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true })).build()
        client.newCall(Request.Builder().url("https://auth.novelia.cc/api/").build()).execute().use {
            assertEquals(401, it.code)
            assertEquals(listOf("first=1", "second=2"), it.headers.values("Set-Cookie"))
            assertEquals(5 * 1024 * 1024, it.body!!.bytes().size)
        }
        assertTrue(cancelled.get())
    }

    @Test fun headRetainsRepresentationLengthButReadsAnEmptyBody() {
        val cancelled = AtomicBoolean()
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute() = EchReply(200, Protocol.HTTP_2,
                    Headers.Builder().add("Content-Length", "512").build(), 512)
                override fun read(maxBytes: Long) = ByteArray(0)
                override fun cancel() { cancelled.set(true) }
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true })).echCallTimeout().build()
        client.newCall(Request.Builder().url("https://n.novelia.cc/file").head().build()).execute().use {
            assertEquals("512", it.header("Content-Length"))
            assertEquals(0L, it.body!!.contentLength())
            assertArrayEquals(ByteArray(0), it.body!!.bytes())
        }
        assertTrue(cancelled.get())
    }

    @Test fun cancellationInterruptsNativeHeaders() {
        val started = CountDownLatch(1); val cancelled = CountDownLatch(1); val completed = CountDownLatch(1)
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute(): EchReply { started.countDown(); assertTrue(cancelled.await(2, TimeUnit.SECONDS)); throw IOException("cancelled") }
                override fun read(maxBytes: Long) = ByteArray(0)
                override fun cancel() { cancelled.countDown() }
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true })).build()
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { completed.countDown() }
            override fun onResponse(call: Call, response: Response) { response.close(); completed.countDown() }
        })
        assertTrue(started.await(2, TimeUnit.SECONDS)); call.cancel()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
    }

    @Test fun unprotectedHostAndDisabledModeUseOriginalClient() {
        val engine = object : EchEngine { override fun open(request: Request, timeouts: EchTimeouts): EchExchange = error("must not route") }
        for ((url, enabled) in listOf("https://example.com/" to true, "https://n.novelia.cc/" to false, "https://n.novelia.cc.example.com/" to true)) {
            val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { enabled })).addInterceptor {
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("").body("ordinary".toResponseBody()).build()
            }.build()
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("ordinary", it.body!!.string()) }
        }
    }

    @Test fun derivedClientTimeoutsReachNativeIncludingUnlimitedZero() {
        val received = mutableListOf<EchTimeouts>()
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                received += timeouts
                return object : EchExchange {
                    override fun execute() = reply()
                    override fun read(maxBytes: Long) = ByteArray(0)
                    override fun cancel() {}
                }
            }
        }
        val original = OkHttpClient.Builder().connectTimeout(150, TimeUnit.MILLISECONDS)
            .readTimeout(250, TimeUnit.MILLISECONDS).writeTimeout(350, TimeUnit.MILLISECONDS)
            .addInterceptor(EchInterceptor(engine, { true })).build()
        val derived = original.newBuilder().connectTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).writeTimeout(0, TimeUnit.MILLISECONDS).build()
        for (client in listOf(original, derived)) {
            client.newCall(Request.Builder().url("https://n.novelia.cc/").build()).execute().close()
        }
        assertEquals(listOf(EchTimeouts(150, 250, 350), EchTimeouts(0, 0, 0)), received)
    }

    @Test fun callTimeoutRemainsActiveDuringResponseBodyRead() {
        val cancelled = CountDownLatch(1)
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute() = reply()
                override fun read(maxBytes: Long): ByteArray {
                    assertTrue("native body did not receive cancellation", cancelled.await(2, TimeUnit.SECONDS))
                    throw IOException("closed")
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        val client = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).addInterceptor(EchInterceptor(engine, { true })).echCallTimeout().build()
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        call.execute().use { response ->
            try { response.body!!.string(); fail("unlimited read ignored total call budget") }
            catch (error: IOException) { assertTrue(call.isCanceled()); assertTrue(error.message in setOf("timeout", "Canceled")) }
        }
        assertEquals(0, cancelled.count)
    }

    @Test fun callTimeoutIncludesEarlierInterceptorWork() {
        val client = OkHttpClient.Builder().callTimeout(600, TimeUnit.MILLISECONDS)
            .addInterceptor { chain -> Thread.sleep(350); chain.proceed(chain.request()) }
            .addInterceptor(EchInterceptor(delayedBodyEngine(), { true })).echCallTimeout().build()
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        assertThrows(InterruptedIOException::class.java) { call.execute().use { it.body!!.bytes() } }
        assertTrue(call.isCanceled())
    }

    @Test fun callTimeoutSurvivesBookSourceRedirectsAndDerivedClients() {
        var attempts = 0
        val bodyEngine = delayedBodyEngine()
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                attempts++
                if (attempts > 1) return bodyEngine.open(request, timeouts)
                return object : EchExchange {
                    override fun execute(): EchReply {
                        Thread.sleep(350)
                        return reply(302, Headers.Builder().add("Location", "/next").build())
                    }
                    override fun read(maxBytes: Long) = ByteArray(0)
                    override fun cancel() {}
                }
            }
        }
        val original = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true })).echCallTimeout().build()
        // Match NoveliaApplication's source routing insertion and download/image derivation.
        val client = original.newBuilder().apply { interceptors().add(0, BookSourceInterceptor(BookSources())) }
            .echCallTimeout().echRedirects(true).callTimeout(600, TimeUnit.MILLISECONDS).build()
        assertSame(EchCallTimeoutInterceptor, client.interceptors.first())
        assertEquals(1, client.interceptors.count { it === EchCallTimeoutInterceptor })
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        assertThrows(InterruptedIOException::class.java) { call.execute().use { it.body!!.bytes() } }
        assertEquals(2, attempts)
        assertTrue(call.isCanceled())
    }

    @Test fun earlierExplicitDeadlineIsPreserved() {
        val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(EchInterceptor(delayedBodyEngine(), { true })).echCallTimeout().build()
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        call.timeout().deadline(150, TimeUnit.MILLISECONDS)
        val deadline = call.timeout().deadlineNanoTime()
        assertThrows(InterruptedIOException::class.java) { call.execute().use { it.body!!.bytes() } }
        assertEquals(deadline, call.timeout().deadlineNanoTime())
    }

    @Test fun zeroCallTimeoutDoesNotInstallADeadline() {
        val client = OkHttpClient.Builder().callTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor(EchInterceptor(delayedBodyEngine(0), { true })).echCallTimeout().build()
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        call.execute().use { assertArrayEquals(byteArrayOf(42), it.body!!.bytes()) }
        assertFalse(call.timeout().hasDeadline())
        assertFalse(call.isCanceled())
    }

    @Test fun asynchronousDispatcherQueueDoesNotConsumeCallTimeout() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>()
        val dispatcher = Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 }
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute(): EchReply {
                    if (request.url.encodedPath == "/hold") {
                        started.countDown()
                        check(release.await(3, TimeUnit.SECONDS))
                    }
                    return reply()
                }
                override fun read(maxBytes: Long) = ByteArray(0)
                override fun cancel() {}
            }
        }
        val client = OkHttpClient.Builder().dispatcher(dispatcher).callTimeout(150, TimeUnit.MILLISECONDS)
            .addInterceptor(EchInterceptor(engine, { true })).echCallTimeout().build()
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) { failure.compareAndSet(null, e); completed.countDown() }
            override fun onResponse(call: Call, response: Response) {
                try { response.use { assertArrayEquals(ByteArray(0), it.body!!.bytes()) } }
                catch (error: Throwable) { failure.compareAndSet(null, error) }
                finally { completed.countDown() }
            }
        }
        val holding = client.newCall(Request.Builder().url("https://n.novelia.cc/hold").build())
        holding.timeout().timeout(0, TimeUnit.MILLISECONDS)
        try {
            holding.enqueue(callback)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            client.newCall(Request.Builder().url("https://n.novelia.cc/queued").build()).enqueue(callback)
            Thread.sleep(250)
            assertEquals(1, dispatcher.queuedCallsCount())
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("queueing consumed the execution budget", it) }
        } finally {
            release.countDown()
            dispatcher.cancelAll()
            dispatcher.executorService.shutdownNow()
        }
    }

    private fun delayedBodyEngine(delayMillis: Long = 350) = object : EchEngine {
        override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
            private var emitted = false
            override fun execute() = reply()
            override fun read(maxBytes: Long): ByteArray {
                if (emitted) return ByteArray(0)
                emitted = true
                Thread.sleep(delayMillis)
                return byteArrayOf(42)
            }
            override fun cancel() {}
        }
    }

    @Test fun responseCloseReleasesCallDeadlineAndCancelsNativeOnce() {
        val cancellations = java.util.concurrent.atomic.AtomicInteger()
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute() = reply()
                override fun read(maxBytes: Long) = ByteArray(0)
                override fun cancel() { cancellations.incrementAndGet() }
            }
        }
        val client = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS)
            .addInterceptor(EchInterceptor(engine, { true })).echCallTimeout().build()
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        call.execute().close()
        Thread.sleep(180)
        assertFalse("closed call was cancelled by a leaked deadline", call.isCanceled())
        assertEquals(1, cancellations.get())
    }

    @Test fun cancellationAlsoInterruptsBlockedResponseBody() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts) = object : EchExchange {
                override fun execute() = reply()
                override fun read(maxBytes: Long): ByteArray {
                    started.countDown()
                    assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                    throw IOException("closed")
                }
                override fun cancel() { cancelled.countDown() }
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true })).build()
        val call = client.newCall(Request.Builder().url("https://n.novelia.cc/").build())
        val response = call.execute()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val reader = Thread {
            try { response.use { it.body!!.string() }; failure.set(AssertionError("read unexpectedly succeeded")) }
            catch (_: IOException) { }
            catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        reader.start()
        assertTrue(started.await(2, TimeUnit.SECONDS)); call.cancel()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError(it) }
    }
}
