package cc.novelia.app.data.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class TurnstilePageTest {
    private val page = verificationPageUrl(AuthTarget.NOVEL, resetting = false, dark = false)

    @Test fun realAuthPageCarriesOnlyPublicRoutingAndTheme() {
        for(target in AuthTarget.entries) for(resetting in listOf(false, true)) for(dark in listOf(false, true)) {
            val url = verificationPageUrl(target, resetting, dark).toHttpUrl()
            assertEquals("https", url.scheme)
            assertEquals("auth.novelia.cc", url.host)
            assertEquals(if(resetting) "/reset-password" else "/register", url.encodedPath)
            assertEquals(setOf("app", "theme"), url.queryParameterNames)
            assertEquals(target.appId, url.queryParameter("app"))
            assertEquals(if(dark) "dark" else "light", url.queryParameter("theme"))
            assertEquals("", url.username)
            assertEquals("", url.password)
            assertNull(url.fragment)
        }
    }

    @Test fun authDocumentAndAssetsAlwaysUseAppTransport() {
        assertEquals(VerificationRoute.APP, verificationRoute(page, "GET", true, page))
        for(asset in listOf("/assets/index-synthetic.js", "/assets/index-synthetic.css", "/assets/font.woff2")) {
            assertEquals(VerificationRoute.APP, verificationRoute("https://auth.novelia.cc$asset", "GET", false, page))
            assertEquals(VerificationRoute.BLOCK, verificationRoute("https://auth.novelia.cc$asset", "POST", false, page))
        }
    }

    @Test fun businessRequestsCannotEscapeThroughWebView() {
        for(host in listOf("auth.novelia.cc", "n.novelia.cc", "forum.novelia.cc", "book.xkvi.top")) {
            for(method in listOf("GET", "POST", "OPTIONS")) {
                for(path in listOf("/api/v1/auth/otp/request", "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/password/reset"))
                    assertEquals(VerificationRoute.BLOCK, verificationRoute("https://$host$path", method, false, page))
            }
        }
        assertEquals(VerificationRoute.BLOCK, verificationRoute(page, "POST", true, page))
        assertEquals(VerificationRoute.BLOCK, verificationRoute("https://auth.novelia.cc/reset-password", "GET", true, page))
    }

    @Test fun onlyHttpsCloudflareSubresourcesCanUseBrowserNetwork() {
        for(method in listOf("GET", "POST", "OPTIONS"))
            assertEquals(VerificationRoute.CLOUDFLARE, verificationRoute("https://challenges.cloudflare.com/turnstile/v0/api.js", method, false, page))
        for(url in listOf("http://challenges.cloudflare.com/a", "https://challenges.cloudflare.com:8443/a",
            "https://challenges.cloudflare.com.example.invalid/a", "https://user@challenges.cloudflare.com/a",
            "https://static.cloudflareinsights.com/a", "file:///synthetic", "intent://synthetic", "https://example.invalid/a"))
            assertEquals(VerificationRoute.BLOCK, verificationRoute(url, "GET", false, page))
        assertEquals(VerificationRoute.BLOCK, verificationRoute("https://challenges.cloudflare.com/a", "GET", true, page))
    }
}
