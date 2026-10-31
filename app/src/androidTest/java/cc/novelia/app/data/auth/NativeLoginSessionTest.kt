package cc.novelia.app.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.network.*
import cc.novelia.app.data.storage.appJson
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** 测试 ECH 使用的是 Session 实际生成的请求，无网络、真实账号或邮件发送。 */
class NativeLoginSessionTest {
    private fun context(): Context {
        val prefix = "native-login-${UUID.randomUUID()}-"
        return object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
    }
    private fun jwt(name: String): String {
        val json = """{"sub":"$name","role":"member","crat":0,"exp":4102444800}"""
        return "e30.${Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}.synthetic"
    }
    private fun payload(request: Request) = appJson.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun reply(body: String = "", cookies: List<String> = emptyList()): EchExchange = object : EchExchange {
        private val bytes = Buffer().writeUtf8(body)
        override fun execute() = EchReply(200, Protocol.HTTP_2, Headers.Builder().apply {
            cookies.forEach { add("Set-Cookie", it) }
        }.build(), -1)
        override fun read(maxBytes: Long) = bytes.readByteArray(minOf(maxBytes, bytes.size))
        override fun cancel() { }
    }
    private fun client(sources: BookSources, respond: (Request) -> EchExchange) = OkHttpClient.Builder()
        .addInterceptor(BookSourceInterceptor(sources))
        .addInterceptor(EchInterceptor(object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                assertEquals("auth.novelia.cc", request.url.host)
                assertEquals("POST", request.method)
                assertNull(request.header("Authorization"))
                assertFalse(request.header("Cookie").orEmpty().contains("accessToken=synthetic-gateway"))
                return respond(request)
            }
        }, { true }))
        .addInterceptor { error("认证请求不得绕过 ECH 或降级直连") }.build()

    @Test fun originalNovelAndForumLoginUseEchAndTheirOwnApplication() = runBlocking {
        val context = context()
        val sources = BookSources(initialToken = "synthetic-gateway")
        AuthTarget.entries.forEach { target ->
            var requests = 0
            val session = Session(context, client(sources) { request ->
                requests++
                assertEquals("/api/v1/auth/login", request.url.encodedPath)
                assertEquals(target.origin, request.header("Origin"))
                assertEquals(target.appId, payload(request).getValue("app").jsonPrimitive.content)
                assertEquals("native-${target.appId}", payload(request).getValue("username").jsonPrimitive.content)
                reply(jwt("native-${target.appId}"))
            }, sources, target)
            assertTrue(session.login(" native-${target.appId} ", "synthetic-password"))
            assertEquals(1, requests)
            assertEquals("native-${target.appId}", Session(context, sources = sources, target = target).profile.value?.username)
        }
        assertEquals("native-n", Session(context, sources = sources).profile.value?.username)
        assertEquals("native-f", Session(context, sources = sources, target = AuthTarget.FORUM).profile.value?.username)
    }

    @Test fun registerRefreshOtpAndPasswordResetAllUseEch() = runBlocking {
        val context = context()
        val sources = BookSources()
        val paths = mutableListOf<String>()
        val cookieName = "native_login_${UUID.randomUUID().toString().replace("-", "")}"
        val session = Session(context, client(sources) { request ->
            paths += request.url.encodedPath
            when(request.url.encodedPath) {
                "/api/v1/auth/otp/request" -> {
                    assertEquals(if(paths.size == 1) "verify" else "reset_password", payload(request).getValue("type").jsonPrimitive.content)
                    assertEquals(setOf("email", "type", "turnstileToken"), payload(request).keys)
                    assertEquals("synthetic-verification", payload(request).getValue("turnstileToken").jsonPrimitive.content)
                    reply()
                }
                "/api/v1/auth/register" -> {
                    assertEquals(setOf("app", "username", "password", "email", "otp"), payload(request).keys)
                    reply(cookies = listOf("$cookieName=synthetic; Secure; HttpOnly; Path=/api/v1/auth"))
                }
                "/api/v1/auth/refresh" -> {
                    assertEquals("n", request.url.queryParameter("app"))
                    assertTrue(request.header("Cookie").orEmpty().contains("$cookieName=synthetic"))
                    reply(jwt("native-user"))
                }
                "/api/v1/auth/password/reset" -> {
                    assertEquals(setOf("email", "password", "otp"), payload(request).keys)
                    assertEquals("0123456789ABCDEFGHIJKLMNOP", payload(request).getValue("otp").jsonPrimitive.content)
                    reply()
                }
                else -> error("Unexpected synthetic endpoint")
            }
        }, sources)
        try {
            session.requestOtp("test@example.invalid", "synthetic-verification")
            assertTrue(session.login("native-user", "synthetic-password", "test@example.invalid", "123456"))
            session.requestOtp("test@example.invalid", "synthetic-verification", resetPassword = true)
            session.resetPassword("test@example.invalid", "synthetic-new-password", "0123456789ABCDEFGHIJKLMNOP")
            assertEquals(listOf("otp/request", "register", "refresh", "otp/request", "password/reset"), paths.map { it.removePrefix("/api/v1/auth/") })
        } finally {
            CookieManager.getInstance().setCookie("${Session.AUTH_URL}/api/v1/auth", "$cookieName=; Max-Age=0; Secure; Path=/api/v1/auth")
            CookieManager.getInstance().flush()
        }
    }

    @Test fun missingHumanVerificationAndOldResetCodeAreRejectedBeforeNetwork() = runBlocking {
        val sources = BookSources()
        var calls = 0
        val session = Session(context(), client(sources) { calls++; reply() }, sources)
        val verification = runCatching { session.requestOtp("test@example.invalid", " ") }.exceptionOrNull()
        assertTrue(verification is IllegalArgumentException)
        assertEquals("请先完成人机验证", verification?.message)
        assertTrue(runCatching { session.requestOtp("test@example.invalid", "a".repeat(2049)) }.exceptionOrNull() is IllegalArgumentException)
        val reset = runCatching { session.resetPassword("test@example.invalid", "synthetic-password", "123456") }.exceptionOrNull()
        assertTrue(reset is IllegalArgumentException)
        assertTrue(reset?.message.orEmpty().contains("26 位字符"))
        assertEquals(0, calls)
        assertNull(session.profile.value)
    }

    @Test fun lateOriginalLoginCannotWriteCookiesOrTokensAfterSwitchingAwayAndBack() = runBlocking {
        val context = context()
        val sources = BookSources(initialToken = "synthetic-gateway")
        val cookieName = "late_native_${UUID.randomUUID().toString().replace("-", "")}"
        val session = Session(context, client(sources) {
            sources.select(BookSource.XKVI)
            sources.select(BookSource.ORIGINAL)
            reply(jwt("late-user"), listOf("$cookieName=synthetic; Secure; Path=/"))
        }, sources)
        assertTrue(runCatching { session.login("late-user", "synthetic-password") }.exceptionOrNull() is SessionChangedException)
        assertNull(session.profile.value)
        assertTrue(context.getSharedPreferences("session", 0).all.isEmpty())
        assertFalse(CookieManager.getInstance().getCookie(Session.AUTH_URL).orEmpty().contains(cookieName))
    }

    @Test fun echFailureDoesNotFallBackOrStoreASession() = runBlocking {
        val context = context()
        val sources = BookSources()
        val session = Session(context, client(sources) { throw IOException("Synthetic ECH failure") }, sources)
        assertTrue(runCatching { session.login("native-user", "synthetic-password") }.exceptionOrNull() is IOException)
        assertNull(session.profile.value)
        assertTrue(context.getSharedPreferences("session", 0).all.isEmpty())
    }
}
