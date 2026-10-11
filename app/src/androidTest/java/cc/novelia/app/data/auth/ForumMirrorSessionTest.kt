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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import cc.novelia.app.data.community.ForumAccountApi
import cc.novelia.app.data.community.ForumApi

/** Synthetic credentials and intercepted responses only; no live authentication or forum writes. */
@RunWith(AndroidJUnit4::class)
class ForumMirrorSessionTest {
    private fun context(): Context {
        val prefix = "forum-mirror-test-${UUID.randomUUID()}-"
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
    }
    private fun jwt(name: String, application: String, expiry: Long = 4_102_444_800L): String {
        val payload = """{"sub":"$name","role":"member","uid":42,"crat":0,"exp":$expiry}"""
        return "e30.${Base64.encodeToString(payload.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}.$application-test-signature"
    }
    private fun seed(context: Context, target: AuthTarget, source: BookSource, token: String, cookie: Boolean = false) {
        val name = target.preferencesName + if(source == BookSource.XKVI) "-xkvi" else ""
        val cipher = DeviceCipher(if(target == AuthTarget.NOVEL) "novelia.session" else "novelia.forum.session")
        val editor = context.getSharedPreferences(name, 0).edit().putString("value", cipher.encrypt(token))
        if(cookie) {
            val saved = MirrorAuthCookies().accept("https://book.xkvi.top/api/v1/auth/login".toHttpUrl(),
                listOf("refresh-session=synthetic-sso; Path=/api/v1/auth; Secure; HttpOnly"))
            editor.putString("cookies", cipher.encrypt(saved))
        }
        assertTrue(editor.commit())
    }
    private fun reply(request: Request, body: String = "", code: Int = 200, cookie: String? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody())
        .apply { cookie?.let { addHeader("Set-Cookie", it) } }.build()
    private fun payload(request: Request) = appJson.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun client(sources: BookSources, handler: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor(BookSourceInterceptor(sources)).addInterceptor { handler(it.request()) }.build()

    @Test fun loginAndUnauthorizedRefreshUseForumTokensAndKeepNovelSessionsSeparate() = runBlocking {
        val context = context()
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val novelToken = jwt("novel-mirror", "n")
        val originalNovel = jwt("novel-original", "n")
        val originalForum = jwt("forum-original", "f")
        seed(context, AuthTarget.NOVEL, BookSource.XKVI, novelToken)
        seed(context, AuthTarget.NOVEL, BookSource.ORIGINAL, originalNovel)
        seed(context, AuthTarget.FORUM, BookSource.ORIGINAL, originalForum)
        val firstForum = jwt("forum-mirror", "f")
        val refreshedForum = jwt("forum-mirror", "f", 4_102_444_801L)
        var refreshes = 0
        var writes = 0
        val client = client(sources) { request ->
            when(request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    assertEquals("book.xkvi.top", request.url.host)
                    assertEquals("POST", request.method)
                    assertEquals("f", payload(request).getValue("app").jsonPrimitive.content)
                    assertEquals(BookSource.XKVI.origin, request.header("Origin"))
                    assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
                    reply(request, firstForum, cookie = "refresh-session=synthetic-forum; Domain=auth.novelia.cc; Path=/api/v1/auth; Secure; HttpOnly")
                }
                "/api/v1/auth/refresh" -> {
                    refreshes++
                    assertEquals("book.xkvi.top", request.url.host)
                    assertEquals("f", request.url.queryParameter("app"))
                    assertEquals("refresh-session=synthetic-forum", request.header("Cookie"))
                    assertNull(request.header("Authorization"))
                    reply(request, refreshedForum)
                }
                "/api/v1/post/5/favorite" -> {
                    writes++
                    assertEquals("book.xkvi.top", request.url.host)
                    assertEquals("PUT", request.method)
                    assertEquals("accessToken=test-gateway-only", request.header("Cookie"))
                    assertEquals("Bearer ${if(writes == 1) firstForum else refreshedForum}", request.header("Authorization"))
                    if(writes == 1) reply(request, code = 502).newBuilder().header("WWW-Authenticate", "Bearer").build()
                    else reply(request, code = 204)
                }
                "/api/v1/me/attention-status" -> {
                    val mirror = sources.capture().source == BookSource.XKVI
                    assertEquals(if(mirror) "book.xkvi.top" else "auth.novelia.cc", request.url.host)
                    assertEquals(if(mirror) "accessToken=test-gateway-only" else null, request.header("Cookie"))
                    assertEquals("Bearer ${if(mirror) refreshedForum else originalForum}", request.header("Authorization"))
                    reply(request, """{"strikes":{"hasUnread":false}}""")
                }
                "/api/v1/me/strikes" -> {
                    assertEquals("book.xkvi.top", request.url.host)
                    assertEquals("GET", request.method)
                    assertEquals("1", request.url.queryParameter("page"))
                    assertEquals("20", request.url.queryParameter("page_size"))
                    assertEquals("accessToken=test-gateway-only", request.header("Cookie"))
                    assertEquals("Bearer $refreshedForum", request.header("Authorization"))
                    reply(request, """{"total":0,"items":[],"latestStrikeId":9007199254740993}""")
                }
                "/api/v1/me/strikes/read-state" -> {
                    assertEquals("book.xkvi.top", request.url.host)
                    assertEquals("PUT", request.method)
                    assertEquals("9007199254740993", payload(request).getValue("throughId").jsonPrimitive.content)
                    assertEquals("accessToken=test-gateway-only", request.header("Cookie"))
                    assertEquals("Bearer $refreshedForum", request.header("Authorization"))
                    reply(request, """{"hasUnread":false}""")
                }
                else -> error("Unexpected synthetic endpoint")
            }
        }
        val main = Session(context, client, sources)
        val forum = Session(context, client, sources, AuthTarget.FORUM)
        assertNull(forum.token)
        assertTrue(forum.loginMirror("forum-mirror", "test-password"))
        val binding = forum.capture()
        assertEquals("forum-xkvi", binding.source)
        ForumApi(NoveliaApi(forum, ForumApi.BASE_URL, client)).favorite(5, true)
        assertEquals(1, refreshes); assertEquals(2, writes)
        val account = ForumAccountApi(NoveliaApi(forum, ForumAccountApi.BASE_URL, client))
        assertFalse(account.attentionStatus().strikes.hasUnread)
        assertFalse(account.markStrikesRead(account.strikes(0).latestStrikeId!!).hasUnread)
        assertEquals(novelToken, main.token)
        assertEquals(refreshedForum, Session(context, client, sources, AuthTarget.FORUM).token)
        val tagged = forum.bindRequest(Request.Builder().url("https://forum.novelia.cc/api/v1/post/").build(), binding)
        assertEquals(sources.capture(), tagged.tag(SourceSelection::class.java))
        sources.select(BookSource.ORIGINAL)
        assertEquals(originalNovel, main.token); assertEquals(originalForum, forum.token)
        assertEquals("forum", forum.capture().source)
        assertTrue(runCatching { account.markStrikesRead(9007199254740993L, binding) }.exceptionOrNull() is SessionChangedException)
        assertFalse(account.attentionStatus().strikes.hasUnread)
        assertThrows(SessionChangedException::class.java) { forum.tokenFor(binding) }
        sources.select(BookSource.XKVI)
        assertEquals(refreshedForum, forum.token)
        forum.logout()
        assertNull(forum.token); assertEquals(novelToken, main.token)
        sources.select(BookSource.ORIGINAL)
        assertEquals(originalForum, forum.token)
    }

    @Test fun sharedMirrorCookieGetsAppFTokenAndSurvivesNovelLogoutWithoutSigningBackInAfterForumLogout() = runBlocking {
        val context = context()
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val novelToken = jwt("shared-user", "n")
        val forumToken = jwt("shared-user", "f")
        seed(context, AuthTarget.NOVEL, BookSource.XKVI, novelToken, cookie = true)
        var calls = 0
        val client = client(sources) { request ->
            calls++
            assertEquals("book.xkvi.top", request.url.host)
            assertEquals("/api/v1/auth/refresh", request.url.encodedPath)
            assertEquals("f", request.url.queryParameter("app"))
            assertEquals("refresh-session=synthetic-sso", request.header("Cookie"))
            assertNull(request.header("Authorization"))
            reply(request, forumToken)
        }
        val main = Session(context, client, sources)
        val forum = Session(context, client, sources, AuthTarget.FORUM)
        assertTrue(forum.loginFromSharedAuth(main))
        assertEquals(forumToken, forum.token); assertEquals(novelToken, main.token)
        assertEquals(forumToken, Session(context, client, sources, AuthTarget.FORUM).token)
        main.logout()
        assertTrue(forum.refresh())
        assertEquals(2, calls)
        seed(context, AuthTarget.NOVEL, BookSource.XKVI, novelToken, cookie = true)
        val restoredMain = Session(context, client, sources)
        forum.logout()
        val restoredForum = Session(context, client, sources, AuthTarget.FORUM)
        assertFalse(restoredForum.loginFromSharedAuth(restoredMain))
        assertEquals(2, calls)
        assertTrue(restoredForum.loginFromSharedAuth(restoredMain, explicit = true))
        assertEquals(3, calls)
        assertEquals(forumToken, restoredForum.token)
    }

    @Test fun sharedMirrorCookieCannotSilentlySelectAnotherForumAccount() = runBlocking {
        val context = context()
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val mainToken = jwt("expected-user", "n")
        seed(context, AuthTarget.NOVEL, BookSource.XKVI, mainToken, cookie = true)
        val client = client(sources) { reply(it, jwt("other-user", "f")) }
        val main = Session(context, client, sources)
        val forum = Session(context, client, sources, AuthTarget.FORUM)
        assertFalse(forum.loginFromSharedAuth(main))
        assertNull(forum.token); assertEquals(mainToken, main.token)
        assertTrue(context.getSharedPreferences("forum-session-xkvi", 0).all.isEmpty())
    }

    @Test fun switchingDuringRefreshRejectsLateForumTokenAndCookie() = runBlocking {
        val context = context()
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val original = jwt("original-user", "f")
        val mirror = jwt("mirror-user", "f")
        seed(context, AuthTarget.FORUM, BookSource.ORIGINAL, original)
        seed(context, AuthTarget.FORUM, BookSource.XKVI, mirror, cookie = true)
        val before = context.getSharedPreferences("forum-session-xkvi", 0).all
        val client = client(sources) { request ->
            sources.select(BookSource.ORIGINAL)
            reply(request, jwt("late-user", "f"), cookie = "refresh-session=late; Path=/api/v1/auth; Secure")
        }
        val forum = Session(context, client, sources, AuthTarget.FORUM)
        assertTrue(runCatching { forum.refresh() }.exceptionOrNull() is SessionChangedException)
        assertEquals(original, forum.token)
        assertEquals(before, context.getSharedPreferences("forum-session-xkvi", 0).all)
        sources.select(BookSource.XKVI)
        assertEquals(mirror, forum.token)
    }

    @Test fun forumRegisterAndOtpUseMirrorWithAppFAndNoGatewayCookie() = runBlocking {
        val context = context()
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val paths = mutableListOf<String>()
        val client = client(sources) { request ->
            paths += request.url.encodedPath
            assertEquals("book.xkvi.top", request.url.host)
            assertNull(request.header("Authorization"))
            assertFalse(request.header("Cookie").orEmpty().contains("accessToken="))
            when(request.url.encodedPath) {
                "/api/v1/auth/otp/request" -> {
                    assertEquals("verify", payload(request).getValue("type").jsonPrimitive.content)
                    reply(request)
                }
                "/api/v1/auth/register" -> {
                    assertEquals("f", payload(request).getValue("app").jsonPrimitive.content)
                    assertEquals("123456", payload(request).getValue("otp").jsonPrimitive.content)
                    reply(request, cookie = "refresh-session=synthetic-registration; Path=/api/v1/auth; Secure")
                }
                "/api/v1/auth/refresh" -> {
                    assertEquals("f", request.url.queryParameter("app"))
                    assertEquals("refresh-session=synthetic-registration", request.header("Cookie"))
                    reply(request, jwt("registered-user", "f"))
                }
                else -> error("Unexpected synthetic endpoint")
            }
        }
        val forum = Session(context, client, sources, AuthTarget.FORUM)
        forum.requestMirrorOtp("test@example.invalid")
        assertTrue(forum.loginMirror("registered-user", "test-password", "test@example.invalid", "123456"))
        assertEquals(listOf("/api/v1/auth/otp/request", "/api/v1/auth/register", "/api/v1/auth/refresh"), paths)
        assertEquals("registered-user", forum.profile.value?.username)
    }
}
