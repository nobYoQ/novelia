package cc.novelia.app.data.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
            override fun open(request: Request, timeoutMillis: Long) = object : EchExchange {
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
            override fun open(request: Request, timeoutMillis: Long): EchExchange {
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
            override fun open(request: Request, timeoutMillis: Long) = object : EchExchange {
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

    @Test fun cancellationInterruptsNativeHeaders() {
        val started = CountDownLatch(1); val cancelled = CountDownLatch(1); val completed = CountDownLatch(1)
        val engine = object : EchEngine {
            override fun open(request: Request, timeoutMillis: Long) = object : EchExchange {
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
        val engine = object : EchEngine { override fun open(request: Request, timeoutMillis: Long): EchExchange = error("must not route") }
        for ((url, enabled) in listOf("https://example.com/" to true, "https://n.novelia.cc/" to false, "https://n.novelia.cc.example.com/" to true)) {
            val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { enabled })).addInterceptor {
                Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("").body("ordinary".toResponseBody()).build()
            }.build()
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("ordinary", it.body!!.string()) }
        }
    }
}
