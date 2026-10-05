package cc.novelia.app.data.auth

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.webkit.CookieManager
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.network.BookSource
import cc.novelia.app.data.network.BookSources
import cc.novelia.app.data.network.SourceSelection
import cc.novelia.app.ui.navigation.ObserveForumLogin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ForumSharedAuthTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anOpenForumPageAutomaticallyFollowsAnOriginalLogin() = withSharedAuth { context, _ ->
        val main = Session(context, client = client { request -> reply(request, jwt("original-user", "main")) })
        val calls = AtomicInteger()
        val forum = Session(context, AuthTarget.FORUM, client { request ->
            calls.incrementAndGet()
            reply(request, jwt("original-user", "forum"))
        })
        compose.setContent {
            ObserveForumLogin(main, forum)
            val profile by forum.profile.collectAsState()
            Text(profile?.username ?: "论坛游客")
        }
        compose.onNodeWithText("论坛游客").assertIsDisplayed()
        assertEquals(0, calls.get())
        assertTrue(main.refresh())
        compose.waitUntil(10_000) { forum.profile.value != null }
        compose.onNodeWithText("original-user").assertIsDisplayed()
        assertEquals(1, calls.get())
    }

    @Test fun reenteringTheForumPageAfterLogoutDoesNotSignBackIn() = withSharedAuth { context, _ ->
        seedMain(context)
        val main = Session(context)
        val calls = AtomicInteger()
        val forum = Session(context, AuthTarget.FORUM, client { request ->
            calls.incrementAndGet()
            reply(request, jwt("original-user", "forum"))
        })
        val showing = mutableStateOf(true)
        compose.setContent {
            if(showing.value) {
                ObserveForumLogin(main, forum)
                val profile by forum.profile.collectAsState()
                Text(profile?.username ?: "论坛游客")
            }
        }
        compose.waitUntil(10_000) { forum.profile.value != null }
        forum.logout()
        compose.onNodeWithText("论坛游客").assertIsDisplayed()
        compose.runOnIdle { showing.value = false }
        compose.waitForIdle()
        compose.runOnIdle { showing.value = true }
        compose.onNodeWithText("论坛游客").assertIsDisplayed()
        assertNull(forum.profile.value)
        assertEquals(1, calls.get())
    }

    @Test fun originalLoginObtainsAndPersistsItsOwnForumToken() = withSharedAuth { context, cookieName ->
        val mainToken = seedMain(context)
        val main = Session(context)
        val mainBinding = main.capture()
        val forumToken = jwt("original-user", "forum")
        val calls = AtomicInteger()
        val client = client { request ->
            calls.incrementAndGet()
            assertEquals("auth.novelia.cc", request.url.host)
            assertEquals("/api/v1/auth/refresh", request.url.encodedPath)
            assertEquals("f", request.url.queryParameter("app"))
            assertEquals(AuthTarget.FORUM.origin, request.header("Origin"))
            assertTrue(request.header("Cookie").orEmpty().contains("$cookieName=synthetic"))
            assertNull(request.header("Authorization"))
            assertNull(request.tag(SourceSelection::class.java))
            reply(request, forumToken)
        }
        val forum = Session(context, AuthTarget.FORUM, client)
        assertTrue(forum.loginFromSharedAuth(main))
        assertEquals("original-user", forum.profile.value?.username)
        assertEquals(forumToken, forum.token)
        assertNotEquals(mainToken, forum.token)
        assertEquals(forumToken, Session(context, AuthTarget.FORUM).token)
        assertEquals(mainToken, main.tokenFor(mainBinding))
        assertTrue(forum.loginFromSharedAuth(main))
        assertEquals(1, calls.get())
    }

    @Test fun explicitLogoutSurvivesRestartUntilTheUserChoosesForumLogin() = withSharedAuth { context, _ ->
        seedMain(context)
        val main = Session(context)
        val calls = AtomicInteger()
        val client = client { request -> calls.incrementAndGet(); reply(request, jwt("original-user", "forum")) }
        val forum = Session(context, AuthTarget.FORUM, client)
        assertTrue(forum.loginFromSharedAuth(main))
        forum.logout()
        val restored = Session(context, AuthTarget.FORUM, client)
        assertFalse(restored.loginFromSharedAuth(main))
        assertNull(restored.token)
        assertEquals(1, calls.get())
        assertTrue(restored.loginFromSharedAuth(main, explicit = true))
        assertEquals(2, calls.get())
        // 显式登录解除退出标记；令牌缺失后仍允许再次自动获取。
        context.getSharedPreferences(AuthTarget.FORUM.preferencesName, Context.MODE_PRIVATE).edit().remove("value").commit()
        assertTrue(Session(context, AuthTarget.FORUM, client).loginFromSharedAuth(main))
        assertEquals(3, calls.get())
    }

    @Test fun guestAndMirrorLoginsDoNotAutomaticallySignIntoTheOriginalForum() = withSharedAuth { context, _ ->
        val calls = AtomicInteger()
        val client = client { request -> calls.incrementAndGet(); reply(request, jwt("original-user", "forum")) }
        val forum = Session(context, AuthTarget.FORUM, client)
        assertFalse(forum.loginFromSharedAuth(Session(context)))
        seedMain(context)
        context.getSharedPreferences("session-xkvi", Context.MODE_PRIVATE).edit()
            .putString("value", DeviceCipher("novelia.session").encrypt(jwt("mirror-user", "mirror"))).commit()
        val mirror = Session(context, sources = BookSources(BookSource.XKVI, "test-gateway-only"))
        assertFalse(forum.loginFromSharedAuth(mirror))
        assertEquals(0, calls.get())
        // 主动论坛登录可复用原站 Cookie，不发送镜像凭据。
        assertTrue(forum.loginFromSharedAuth(mirror, explicit = true))
        assertEquals("original-user", forum.profile.value?.username)
        assertEquals("mirror-user", mirror.profile.value?.username)
    }

    @Test fun expiredSharedAuthenticationLeavesTheForumAnonymousAndCanBeRetried() = withSharedAuth { context, _ ->
        val mainToken = seedMain(context)
        val main = Session(context)
        val calls = AtomicInteger()
        val forum = Session(context, AuthTarget.FORUM, client { request ->
            reply(request, jwt("original-user", "forum"), if(calls.incrementAndGet() == 1) 401 else 200)
        })
        val anonymousBinding = forum.capture()
        assertFalse(forum.loginFromSharedAuth(main))
        assertEquals(anonymousBinding, forum.capture())
        assertNull(forum.profile.value)
        assertNull(Session(context, AuthTarget.FORUM).token)
        assertEquals(mainToken, main.token)
        assertTrue(forum.loginFromSharedAuth(main))
        assertEquals(2, calls.get())
    }

    @Test fun aSharedCookieForAnotherAccountCannotSilentlyChangeTheForumAccount() = withSharedAuth { context, _ ->
        seedMain(context)
        val forum = Session(context, AuthTarget.FORUM, client { request -> reply(request, jwt("other-user", "forum")) })
        assertFalse(forum.loginFromSharedAuth(Session(context)))
        assertNull(forum.profile.value)
        assertNull(Session(context, AuthTarget.FORUM).token)
    }

    @Test fun aLateResponseCannotUndoForumLogout() = withSharedAuth { context, _ ->
        seedMain(context)
        val main = Session(context)
        lateinit var forum: Session
        val calls = AtomicInteger()
        forum = Session(context, AuthTarget.FORUM, client { request ->
            calls.incrementAndGet()
            forum.clear()
            reply(request, jwt("original-user", "forum"))
        })
        assertTrue(runCatching { forum.loginFromSharedAuth(main) }.exceptionOrNull() is SessionChangedException)
        assertNull(Session(context, AuthTarget.FORUM).token)
        assertFalse(forum.loginFromSharedAuth(main))
        assertEquals(1, calls.get())
    }

    @Test fun aLateResponseCannotCommitAfterMainLogout() = withSharedAuth { context, _ ->
        seedMain(context)
        val main = Session(context)
        val forum = Session(context, AuthTarget.FORUM, client { request ->
            main.clear()
            reply(request, jwt("original-user", "forum"))
        })
        assertTrue(runCatching { forum.loginFromSharedAuth(main) }.exceptionOrNull() is SessionChangedException)
        assertNull(Session(context, AuthTarget.FORUM).token)
    }

    @Test fun aLateResponseCannotCommitAfterSwitchingNovelSources() = withSharedAuth { context, _ ->
        seedMain(context)
        val sources = BookSources(initialToken = "test-gateway-only")
        val main = Session(context, sources = sources)
        val forum = Session(context, AuthTarget.FORUM, client { request ->
            sources.select(BookSource.XKVI)
            reply(request, jwt("original-user", "forum"))
        })
        assertTrue(runCatching { forum.loginFromSharedAuth(main) }.exceptionOrNull() is SessionChangedException)
        assertNull(Session(context, AuthTarget.FORUM).token)
    }

    private fun withSharedAuth(block: suspend (Context, String) -> Unit) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "test-forum-sso-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        val cookieName = "forum_sso_" + UUID.randomUUID().toString().replace("-", "")
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(Session.AUTH_URL, "$cookieName=synthetic; Secure; Path=/") { ready.countDown() }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        try { block(context, cookieName) } finally {
            AuthTarget.entries.forEach { context.getSharedPreferences(it.preferencesName, Context.MODE_PRIVATE).edit().clear().commit() }
            context.getSharedPreferences("session-xkvi", Context.MODE_PRIVATE).edit().clear().commit()
            val cleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(Session.AUTH_URL, "$cookieName=; Max-Age=0; Path=/") { cleared.countDown() }
            }
            assertTrue(cleared.await(10, TimeUnit.SECONDS))
        }
    }

    private fun seedMain(context: Context): String = jwt("original-user", "main").also {
        context.getSharedPreferences(AuthTarget.NOVEL.preferencesName, Context.MODE_PRIVATE).edit()
            .putString("value", DeviceCipher("novelia.session").encrypt(it)).commit()
    }

    private fun jwt(username: String, signature: String): String {
        val payload = """{"sub":"$username","role":"member","uid":42,"crat":1600000000,"exp":4102444800}"""
        return "e30." + Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + ".$signature-synthetic"
    }

    private fun client(respond: (Request) -> Response) = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
    private fun reply(request: Request, body: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build()
}
