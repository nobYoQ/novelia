package cc.novelia.app.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.auth.Session
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.network.BookSource
import cc.novelia.app.data.network.BookSources
import cc.novelia.app.data.network.SourceSelection
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class ForumSessionIsolationTest {
    @Test fun signingOutForumPreservesMainAndSharedCookies() = verifyIsolation(AuthTarget.FORUM)
    @Test fun signingOutMainPreservesForumAndSharedCookies() = verifyIsolation(AuthTarget.NOVEL)

    @Test fun standaloneForumSessionIgnoresUnrelatedBookSourceChanges() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "test-forum-mirror-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val cookieReady = CountDownLatch(1)
        val cookieName = "forum_isolation_" + UUID.randomUUID().toString().replace("-", "")
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(Session.AUTH_URL, "$cookieName=synthetic; Secure; Path=/") { cookieReady.countDown() }
        }
        assertTrue(cookieReady.await(10, TimeUnit.SECONDS))
        try {
            val synthetic = seedSession(context, AuthTarget.FORUM, "forum-test")
            val novelSynthetic = seedSession(context, AuthTarget.NOVEL, "novel-test")
            context.getSharedPreferences("session-xkvi", Context.MODE_PRIVATE).edit()
                .putString("value", DeviceCipher("novelia.session").encrypt(novelSynthetic)).commit()
            var refreshes = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("auth.novelia.cc", request.url.host)
                assertEquals("/api/v1/auth/refresh", request.url.encodedPath)
                assertEquals("f", request.url.queryParameter("app"))
                assertEquals(AuthTarget.FORUM.origin, request.header("Origin"))
                assertNull(request.tag(SourceSelection::class.java))
                assertFalse(request.header("Cookie").orEmpty().contains("test-gateway-only"))
                // 小说在请求途中切换来源，论坛的刷新结果仍属于同一会话。
                sources.select(BookSource.ORIGINAL)
                refreshes++
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .body(synthetic.toResponseBody()).build()
            }.build()
            val forum = Session(context, client = client, target = AuthTarget.FORUM)
            val binding = forum.capture()
            assertEquals("forum-test", forum.profile.value?.username)
            assertEquals(42L, forum.profile.value?.userId)
            assertEquals("forum", binding.source)
            assertTrue(forum.refresh())
            assertEquals(1, refreshes)
            assertEquals(binding, forum.capture())
            sources.select(BookSource.XKVI)
            val authRequest = Request.Builder().url("${Session.AUTH_URL}/api/v1/user/me").build()
            assertSame(authRequest, forum.bindRequest(authRequest, binding))
            assertEquals(synthetic, forum.tokenFor(binding))
            assertEquals(synthetic, Session(context, AuthTarget.FORUM).token)
            // 退出镜像只清理镜像；原站和论坛账号均保留。
            val mirror = Session(context, sources = sources)
            assertNotNull(mirror.token)
            mirror.logout()
            assertNull(mirror.token)
            assertNull(Session(context, sources = sources).token)
            assertEquals(synthetic, forum.tokenFor(binding))
            sources.select(BookSource.ORIGINAL)
            assertEquals("novel-test", Session(context, sources = sources).profile.value?.username)
        } finally {
            AuthTarget.entries.forEach { context.getSharedPreferences(it.preferencesName, Context.MODE_PRIVATE).edit().clear().commit() }
            context.getSharedPreferences("session-xkvi", Context.MODE_PRIVATE).edit().clear().commit()
            instrumentation.runOnMainSync { CookieManager.getInstance().setCookie(Session.AUTH_URL, "$cookieName=; Max-Age=0; Path=/") }
        }
    }

    private fun verifyIsolation(target: AuthTarget) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "test-session-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        val cookieUrl = "https://session-isolation.invalid"
        val cookieReady = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(cookieUrl, "synthetic=retained; Secure; Path=/") { cookieReady.countDown() }
        }
        assertTrue(cookieReady.await(10, TimeUnit.SECONDS))
        try {
            seedSession(context, AuthTarget.NOVEL, "main-test")
            seedSession(context, AuthTarget.FORUM, "forum-test")
            val main = Session(context)
            val forum = Session(context, AuthTarget.FORUM)
            assertEquals("main-test", main.profile.value?.username)
            assertEquals("forum-test", forum.profile.value?.username)
            val leaving = if(target == AuthTarget.FORUM) forum else main
            val remaining = if(target == AuthTarget.FORUM) main else forum
            val previous = leaving.token
            val previousBinding = leaving.capture()
            val preserved = remaining.token
            val preservedBinding = remaining.capture()
            leaving.logout()
            assertNull(leaving.token)
            assertNull(leaving.profile.value)
            assertEquals(preserved, remaining.token)
            assertEquals(preservedBinding, remaining.capture())
            assertEquals(preserved, Session(context, remaining.target).token)
            assertNull(Session(context, target).token)
            assertTrue(CookieManager.getInstance().getCookie(cookieUrl).contains("synthetic=retained"))
            assertTrue(runCatching { leaving.refreshIfCurrent(previousBinding, previous) }.exceptionOrNull() is SessionChangedException)
            assertFalse(leaving.refreshIfCurrent(leaving.capture(), previous))
            assertFalse(leaving.refreshIfCurrent(leaving.capture(), null))
        } finally {
            AuthTarget.entries.forEach { context.getSharedPreferences(it.preferencesName, Context.MODE_PRIVATE).edit().clear().commit() }
            instrumentation.runOnMainSync { CookieManager.getInstance().setCookie(cookieUrl, "synthetic=; Max-Age=0; Path=/") }
        }
    }

    /** Synthetic signed-in state, encrypted normally; no real credentials or remote requests. */
    private fun seedSession(context: Context, target: AuthTarget, username: String): String {
        val alias = if(target == AuthTarget.NOVEL) "novelia.session" else "novelia.forum.session"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val payload = """{"sub":"$username","role":"member","uid":42,"crat":1600000000,"exp":4102444800}"""
        val synthetic = "e30." + Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".synthetic"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        context.getSharedPreferences(target.preferencesName, Context.MODE_PRIVATE).edit().putString("value", Base64.encodeToString(cipher.iv + cipher.doFinal(synthetic.toByteArray()), Base64.NO_WRAP)).commit()
        return synthetic
    }
}
