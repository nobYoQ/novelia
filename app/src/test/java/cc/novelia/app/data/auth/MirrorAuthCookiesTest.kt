package cc.novelia.app.data.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MirrorAuthCookiesTest {
    private val login = "https://book.xkvi.top/api/v1/auth/login".toHttpUrl()
    private val refresh = "https://book.xkvi.top/api/v1/auth/refresh?app=n".toHttpUrl()

    @Test fun mappedAuthCookieSurvivesRestartAndRespectsHostPathAndSecure() {
        val saved = MirrorAuthCookies().accept(login, listOf("refresh-session=test-session; Domain=auth.novelia.cc; Path=/api/v1/auth; Secure; HttpOnly"))
        val cookies = MirrorAuthCookies(saved)
        assertEquals("refresh-session=test-session", cookies.header(refresh))
        assertEquals("", cookies.header("https://book.xkvi.top/api/novel".toHttpUrl()))
        assertEquals("", cookies.header("https://auth.novelia.cc/api/v1/auth/refresh".toHttpUrl()))
        assertEquals("", cookies.header("http://book.xkvi.top/api/v1/auth/refresh".toHttpUrl()))
    }
    @Test fun remoteCookiesCannotReplaceGatewayOrSetForeignDomains() {
        val cookies = MirrorAuthCookies()
        cookies.accept(login, listOf("accessToken=other; Path=/", "session=foreign; Domain=example.com; Path=/", "session=valid; Path=/; Secure"))
        assertEquals("session=valid", cookies.header(refresh))
    }
    @Test fun refreshReplacesAndLogoutExpiryRemovesCookie() {
        val cookies = MirrorAuthCookies()
        cookies.accept(login, listOf("refresh=old; Path=/; Secure"))
        cookies.accept(refresh, listOf("refresh=new; Path=/; Secure"))
        assertEquals("refresh=new", MirrorAuthCookies(cookies.accept(refresh, emptyList())).header(refresh))
        cookies.accept(refresh, listOf("refresh=; Max-Age=0; Path=/; Secure"))
        assertEquals("", cookies.header(refresh))
    }
}
