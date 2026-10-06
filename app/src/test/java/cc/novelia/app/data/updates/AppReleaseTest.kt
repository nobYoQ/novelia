package cc.novelia.app.data.updates

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    @Test fun comparesVersionNumbersAndPrereleaseIdentifiersSemantically() {
        assertTrue(isNewerAppVersion("0.2.9", "v0.2.10"))
        assertTrue(isNewerAppVersion("1.0.0-beta.2", "1.0.0-beta.11"))
        assertTrue(isNewerAppVersion("1.0.0-rc.1", "1.0.0"))
        assertFalse(isNewerAppVersion("1.1.0", "v1.0.9"))
        assertFalse(isNewerAppVersion("1.0.0", "1.0.0-beta.1"))
        assertFalse(isNewerAppVersion("1.0.0+build.1", "v1.0.0+build.2"))
        assertFalse(isNewerAppVersion("0.2.6", "preview"))
        assertFalse(isNewerAppVersion("0.2.6", "1.0.0-beta.01"))
    }

    @Test fun onlyNewOfficialStableReleasesOfferAnUpdate() {
        val release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0")
        assertTrue(release.availableFor("0.2.6"))
        assertFalse(release.availableFor("0.3.0"))
        assertFalse(release.copy(draft = true).availableFor("0.2.6"))
        assertFalse(release.copy(prerelease = true).availableFor("0.2.6"))
        assertFalse(release.copy(url = "https://github.com/other/project/releases/tag/v0.3.0").availableFor("0.2.6"))
        assertFalse(release.copy(url = "http://github.com/nobYoQ/novelia/releases/tag/v0.3.0").availableFor("0.2.6"))
    }

    @Test fun publicReleaseRequestParsesUnknownFieldsWithoutSendingAccountCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v0.3.0","html_url":"$APP_RELEASES_URL/tag/v0.3.0","name":"新版本","body":"更新内容","assets":[],"draft":false,"prerelease":false}"""))
            val release = AppReleaseClient(endpoint = server.url("/latest").toString()).latest()!!
            assertEquals("新版本", release.name)
            assertTrue(release.availableFor("0.2.6"))
            val request = server.takeRequest()
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun noReleaseIsQuietButHttpErrorsAreReportedToManualChecks() = runBlocking {
        MockWebServer().use { server ->
            val client = AppReleaseClient(endpoint = server.url("/latest").toString())
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(client.latest())
            server.enqueue(MockResponse().setResponseCode(403))
            try { client.latest(); fail("Expected HTTP error") } catch(expected: IOException) { assertTrue(expected.message!!.contains("403")) }
        }
    }
}
