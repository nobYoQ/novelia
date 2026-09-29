package cc.novelia.app

import cc.novelia.app.data.catalog.ClipboardLinkHistory
import cc.novelia.app.data.catalog.ClipboardLinks
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.storage.appJson
import java.net.URLDecoder
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ClipboardLinksTest {
    @Test fun supportedSitePagesOpenTheirNativeScreen() {
        mapOf(
            "novel/syosetu/n1234" to "book/syosetu/n1234",
            "novel/syosetu/n1234/20" to "reader/syosetu/n1234/20",
            "wenku/abc123" to "book/wenku/abc123",
            "forum/abc123" to "article/abc123",
            "forum" to "community",
            "setting" to "settings",
            "read-history" to "history",
            "novel-list" to "discover",
        ).forEach { (path, route) ->
            assertEquals(path, route, ClipboardLinks.find("推荐看看：https://n.novelia.cc/$path。")?.route)
        }
    }

    @Test fun hostCaseAndDefaultPortAreNormalizedBeforeRouting() {
        val link = ClipboardLinks.find("HTTPS://N.NOVELIA.CC:443/novel/syosetu/n1234")!!
        assertEquals("https://n.novelia.cc/novel/syosetu/n1234", link.url)
        assertEquals("book/syosetu/n1234", link.route)
        assertEquals(link, ClipboardLinks.find("http://n.novelia.cc/novel/syosetu/n1234"))
    }

    @Test fun queriesAndAnchorsArePreservedInTheInAppBrowser() {
        listOf(
            "https://n.novelia.cc/novel?query=cat%26dog&page=2",
            "https://n.novelia.cc/forum/abc123#comment-456",
            "https://n.novelia.cc/novel/syosetu/n1234?source=%E5%B0%8F%E8%AF%B4",
        ).forEach { url ->
            val link = ClipboardLinks.find(url)!!
            assertTrue(link.route.startsWith("web?url="))
            assertEquals(url, URLDecoder.decode(link.route.removePrefix("web?url="), "UTF-8"))
        }
    }

    @Test fun unsupportedAndMalformedLinksDoNotCreateSuggestions() {
        listOf(
            "普通文本", "https://example.com/novel/syosetu/n1234",
            "https://n.novelia.cc.attacker.test/novel/syosetu/n1234",
            "https://n.novelia.cc@attacker.test/novel/syosetu/n1234",
            "https://user@n.novelia.cc/novel/syosetu/n1234",
            "https://n.novelia.cc:8443/novel/syosetu/n1234",
            "ftp://n.novelia.cc/novel/syosetu/n1234",
            "https://n.novelia.cc/api/user", "https://n.novelia.cc/login",
            "https://n.novelia.cc/novel/unknown/n1234",
            "https://n.novelia.cc/novel/syosetu/%2e%2e",
            "https://n.novelia.cc/novel/syosetu/n1234%2f20",
            "https://n.novelia.cc/novel/syosetu/n1234/20/extra",
            "https://n.novelia.cc/novel/syosetu/%ZZ",
        ).forEach { assertNull(it, ClipboardLinks.find(it)) }
        assertNull(ClipboardLinks.find(null))
    }

    @Test fun sharedTextSkipsUnrelatedLinksAndStopsAtTheFirstSupportedPage() {
        val link = ClipboardLinks.find("来源 https://example.com/\n推荐（https://n.novelia.cc/wenku/abc123）。\nhttps://n.novelia.cc/forum/abc")
        assertEquals("book/wenku/abc123", link?.route)
    }

    @Test fun returningToTheAppDoesNotRepeatRecentSuggestions() {
        val history = ClipboardLinkHistory()
        val url = "https://n.novelia.cc/novel/syosetu/n1234"
        assertNotNull(history.next(url))
        assertNull(history.next("看看这个：$url。"))
        assertNull(history.next("没有网址"))
        assertNull(history.next(url))
        assertNotNull(history.next("https://n.novelia.cc/wenku/abc123"))
    }

    @Test fun clipboardPreferenceSurvivesBackupsAndOldSettingsRemainReadable() {
        assertTrue(appJson.decodeFromString<SettingsBackup>("{}").clipboardLinkHints)
        assertTrue(appJson.decodeFromString<LibraryState>("{}").clipboardLinkHints)
        val backup = SettingsBackup(clipboardLinkHints = false)
        assertFalse(appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(backup)).clipboardLinkHints)
        val state = LibraryState(clipboardLinkHints = false)
        assertFalse(appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)).clipboardLinkHints)
    }
}
