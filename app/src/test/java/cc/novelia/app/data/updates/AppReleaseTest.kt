package cc.novelia.app.data.updates

import java.io.IOException
import cc.novelia.app.ui.components.friendlyMessage
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private fun apk(name: String, tag: String = "v0.3.0") = AppReleaseAsset(
        name, "$APP_RELEASES_URL/download/$tag/$name", size = 1024, state = "uploaded")

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

    @Test fun selectsApkInDeviceAbiOrderAndFallsBackToUniversal() {
        val arm32 = apk("Novelia-0.3.0-armeabi-v7a.apk")
        val arm64 = apk("Novelia-0.3.0-arm64-v8a.apk")
        val x64 = apk("Novelia-0.3.0-x86_64.apk")
        val universal = apk("Novelia-0.3.0-universal.apk")
        val release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0", assets = listOf(universal, arm32, x64, arm64))
        assertEquals(arm64, release.apkFor(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(arm32, release.apkFor(listOf("armeabi-v7a")))
        assertEquals(x64, release.apkFor(listOf("x86_64", "x86")))
        assertEquals(universal, release.apkFor(listOf("x86")))
        assertNull(release.copy(assets = listOf(x64)).apkFor(listOf("x86")))
        assertNull(release.copy(assets = listOf(arm64)).apkFor(listOf("armeabi-v7a")))
    }

    @Test fun supportsLegacySingleApkButRejectsAmbiguousAndNonInstallableAttachments() {
        val legacy = apk("Novelia-0.3.0-release.apk")
        val release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0", assets = listOf(legacy))
        val abis = listOf("arm64-v8a")
        assertEquals(legacy, release.apkFor(abis))
        assertNull(release.copy(assets = listOf(legacy, apk("app-release.apk"))).apkFor(abis))
        for(invalid in listOf(
            apk("Novelia-0.3.0.apk.sha256"), apk("Novelia-0.3.0-source.zip"),
            apk("app-debug.apk"), apk("app-release-unsigned.apk"), apk("app-androidTest.apk"),
            apk("Novelia-preview-staging-123-Novelia-preview-arm64-v8a.apk"),
            legacy.copy(size = 0), legacy.copy(state = "new"), apk("../app.apk"),
        )) assertNull(invalid.name, release.copy(assets = listOf(invalid)).apkFor(abis))
    }

    @Test fun onlyDownloadsCompleteApksBelongingToTheSelectedOfficialRelease() {
        val asset = apk("Novelia-0.3.0-arm64-v8a.apk")
        val release = AppRelease("v0.3.0", "$APP_RELEASES_URL/tag/v0.3.0", assets = listOf(asset))
        val abis = listOf("arm64-v8a")
        val invalidUrls = listOf(
            asset.downloadUrl.replace("https:", "http:"),
            asset.downloadUrl.replace("github.com", "github.com.evil.test"),
            asset.downloadUrl.replace("nobYoQ/novelia", "other/novelia"),
            asset.downloadUrl.replace("v0.3.0", "v0.2.9"),
            asset.downloadUrl.replace("arm64-v8a.apk", "x86.apk"),
            asset.downloadUrl.replace("github.com", "user@github.com"),
            asset.downloadUrl.replace("github.com", "github.com:8443"),
            asset.downloadUrl + "?download=1", asset.downloadUrl + "#fragment",
        )
        for(url in invalidUrls) assertNull(url, release.copy(assets = listOf(asset.copy(downloadUrl = url))).apkFor(abis))
        assertNull(release.copy(draft = true).apkFor(abis))
        assertNull(release.copy(url = release.url + "-other").apkFor(abis))
        val preview = AppRelease("preview", "$APP_RELEASES_URL/tag/preview", prerelease = true,
            assets = listOf(apk("Novelia-preview-arm64-v8a.apk", "preview")))
        assertNotNull(preview.apkFor(abis))
        assertFalse(preview.availableFor("0.2.6"))
    }

    @Test fun publicReleaseRequestParsesUnknownFieldsWithoutSendingAccountCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v0.3.0","html_url":"$APP_RELEASES_URL/tag/v0.3.0","name":"新版本","body":"更新内容","assets":[{"id":123,"name":"Novelia-0.3.0-arm64-v8a.apk","browser_download_url":"$APP_RELEASES_URL/download/v0.3.0/Novelia-0.3.0-arm64-v8a.apk","size":2048,"state":"uploaded","content_type":"application/octet-stream"}],"draft":false,"prerelease":false}"""))
            val release = AppReleaseClient(endpoint = server.url("/latest").toString()).latest()!!
            assertEquals("新版本", release.name)
            assertTrue(release.availableFor("0.2.6"))
            assertEquals(2048L, release.apkFor(listOf("arm64-v8a"))!!.size)
            val request = server.takeRequest()
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun previewUsesItsOwnEndpointAndDoesNotRequireASemanticVersion() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"tag_name":"preview","html_url":"$APP_RELEASES_URL/tag/preview","prerelease":true,"assets":[{"name":"Novelia-preview-arm64-v8a.apk","browser_download_url":"$APP_RELEASES_URL/download/preview/Novelia-preview-arm64-v8a.apk","size":1024,"state":"uploaded"}]}"""
            val client = AppReleaseClient(endpoint = server.url("/latest").toString(),
                previewEndpoint = server.url("/releases/tags/preview").toString())
            server.enqueue(MockResponse().setBody(body))
            val preview = client.preview()!!
            assertNotNull(preview.apkFor(listOf("arm64-v8a")))
            assertFalse(preview.availableFor("0.2.6"))
            val request = server.takeRequest()
            assertEquals("/releases/tags/preview", request.path)
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(client.preview())
            server.enqueue(MockResponse().setBody(body.replace("\"prerelease\":true", "\"prerelease\":false")))
            assertNull(client.preview())
            server.enqueue(MockResponse().setBody(body.replace("\"prerelease\":true", "\"prerelease\":true,\"draft\":true")))
            assertNull(client.preview())
            server.enqueue(MockResponse().setResponseCode(429))
            try { client.preview(); fail("Expected HTTP error") } catch(expected: IOException) { assertTrue(expected.message!!.contains("429")) }
        }
    }

    @Test fun noReleaseIsQuietButHttpErrorsAreReportedToManualChecks() = runBlocking {
        MockWebServer().use { server ->
            val client = AppReleaseClient(endpoint = server.url("/latest").toString())
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(client.latest())
            server.enqueue(MockResponse().setResponseCode(403))
            try { client.latest(); fail("Expected HTTP error") } catch(expected: IOException) { assertTrue(expected.friendlyMessage().contains("403")) }
        }
    }

    @Test fun missingApkReasonReachesTheUser() {
        val reason = "此发行版暂未提供适合当前设备的 APK"
        assertEquals(reason, AppReleaseException(reason).friendlyMessage())
    }

    @Test fun anInstalledStableReleaseDoesNotNeedACompatibleAssetOrAnyDownload() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v0.3.0","html_url":"$APP_RELEASES_URL/tag/v0.3.0","assets":[]}"""))
            val client = AppReleaseClient(endpoint = server.url("/latest").toString())
            assertNull(client.candidate(AppReleaseChannel.Stable, listOf("x86"), "0.3.0"))
            assertEquals(1, server.requestCount)
        }
    }
}
