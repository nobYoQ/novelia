package cc.novelia.app.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.network.*
import cc.novelia.app.data.storage.appJson
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MirrorSessionTest {
    private fun context(): Context {
        val prefix = "mirror-test-${UUID.randomUUID()}-"
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
    }
    private fun jwt(name: String, expiry: Long = 4_102_444_800L): String {
        fun encode(value: String) = Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "${encode("{\"alg\":\"none\"}")}.${encode("{\"sub\":\"$name\",\"role\":\"member\",\"crat\":0,\"exp\":$expiry}")}.test-signature"
    }
    private fun reply(request: Request, code: Int = 200, body: String = "", cookie: String? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody())
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun payload(request: Request) = appJson.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    @Test fun mirrorLoginRefreshAndLogoutAreIsolatedFromOriginalAndSurviveRestart() = runBlocking {
        val context = context()
        val originalJwt = jwt("original-user")
        context.getSharedPreferences("session", 0).edit().putString("value", DeviceCipher("novelia.session").encrypt(originalJwt)).commit()
        val sources = BookSources(initialToken = "test-gateway-only")
        var refreshes = 0
        var contentRequests = 0
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
            val request = chain.request()
            assertEquals("book.xkvi.top", request.url.host)
            assertEquals(!isAuthApiPath(request.url.encodedPath), request.header("Cookie").orEmpty().contains("accessToken=test-gateway-only"))
            assertFalse(request.header("Authorization").orEmpty().contains(originalJwt))
            when(request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    assertEquals("n", payload(request).getValue("app").jsonPrimitive.content)
                    assertEquals("mirror-user", payload(request).getValue("username").jsonPrimitive.content)
                    reply(request, cookie = "refresh-session=test-session; Domain=auth.novelia.cc; Path=/api/v1/auth; Secure; HttpOnly")
                }
                "/api/v1/auth/refresh" -> {
                    refreshes++
                    assertEquals("n", request.url.queryParameter("app"))
                    assertTrue(request.header("Cookie").orEmpty().contains("refresh-session=test-session"))
                    reply(request, body = jwt("mirror-user", 4_102_444_800L + refreshes))
                }
                "/api/novel" -> {
                    contentRequests++
                    reply(request, if(contentRequests == 1) 502 else 200, "{}").newBuilder()
                        .header("WWW-Authenticate", "Bearer realm=\"Access token\"").build()
                }
                "/api/v1/auth/logout" -> reply(request)
                else -> error("Unexpected test endpoint")
            }
        }.build()
        val session = Session(context, client, sources)
        assertEquals("original-user", session.profile.value?.username)
        val originalBinding = session.capture()
        sources.select(BookSource.XKVI)
        assertNull(session.profile.value)
        assertThrows(SessionChangedException::class.java) { session.tokenFor(originalBinding) }
        assertTrue(session.loginMirror("mirror-user", "test-password"))
        val mirrorBinding = session.capture()
        assertEquals("mirror-user", session.profile.value?.username)
        assertEquals("{}", NoveliaApi(session, transport = client).request("GET", "novel"))
        assertEquals(2, refreshes); assertEquals(2, contentRequests)
        assertEquals(mirrorBinding, session.capture())
        // 新实例仍能读取单独保存并加密的镜像 JWT/Cookie。
        val restored = Session(context, client, BookSources(BookSource.XKVI, "test-gateway-only"))
        assertEquals("mirror-user", restored.profile.value?.username)
        assertEquals(session.token, restored.token)
        session.logout()
        assertNull(session.profile.value)
        sources.select(BookSource.ORIGINAL)
        assertEquals(originalJwt, session.token)
        assertThrows(SessionChangedException::class.java) { session.tokenFor(mirrorBinding) }
        sources.select(BookSource.XKVI)
        assertNull(session.profile.value)
    }

    @Test fun registerAndOtpUseOriginalContractWithoutSendingAccountBearer() = runBlocking {
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            assertNull(request.header("Authorization"))
            when(request.url.encodedPath) {
                "/api/v1/auth/otp/request" -> {
                    assertEquals("verify", payload(request).getValue("type").jsonPrimitive.content)
                    assertEquals("test@example.invalid", payload(request).getValue("email").jsonPrimitive.content)
                    reply(request)
                }
                "/api/v1/auth/register" -> {
                    assertEquals(setOf("app", "username", "password", "email", "otp"), payload(request).keys)
                    assertEquals("123456", payload(request).getValue("otp").jsonPrimitive.content)
                    reply(request, cookie = "refresh=test-session; Path=/; Secure; HttpOnly")
                }
                else -> reply(request, body = jwt("test-user"))
            }
        }.build()
        val session = Session(context(), client, sources)
        session.requestMirrorOtp("test@example.invalid")
        assertTrue(session.loginMirror("test-user", "test-password", "test@example.invalid", "123456"))
        assertEquals(listOf("/api/v1/auth/otp/request", "/api/v1/auth/register", "/api/v1/auth/refresh"), paths)
    }

    @Test fun loginCanAcceptReturnedJwtWithoutRequiringAnotherRefresh() = runBlocking {
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
            calls++
            assertEquals("/api/v1/auth/login", chain.request().url.encodedPath)
            reply(chain.request(), body = jwt("test-user"))
        }.build()
        val session = Session(context(), client, sources)
        assertTrue(session.loginMirror("test-user", "test-password"))
        assertEquals("test-user", session.profile.value?.username)
        assertEquals(1, calls)
    }

    @Test fun delayedLoginCannotCommitAfterSourceSwitch() = runBlocking {
        val context = context()
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
            sources.select(BookSource.ORIGINAL)
            reply(chain.request(), cookie = "refresh=late-session; Path=/; Secure")
        }.build()
        val session = Session(context, client, sources)
        assertTrue(runCatching { session.loginMirror("test-user", "test-password") }.exceptionOrNull() is SessionChangedException)
        sources.select(BookSource.XKVI)
        assertNull(session.profile.value)
        assertTrue(context.getSharedPreferences("session-xkvi", 0).all.isEmpty())
    }
}
