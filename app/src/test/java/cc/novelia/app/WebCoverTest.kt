package cc.novelia.app

import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.WebCoverPalette
import cc.novelia.app.ui.components.webCoverPalette
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WebCoverTest {
    private val ref = BookRef("syosetu", "story")

    @Test fun listDetailAndPersistedShelfUseTheSameSixClassifications() {
        val general = listOf(WebCoverPalette.GeneralSerial, WebCoverPalette.GeneralCompleted, WebCoverPalette.GeneralShort)
        val adult = listOf(WebCoverPalette.AdultSerial, WebCoverPalette.AdultCompleted, WebCoverPalette.AdultShort)
        for ((index, type) in listOf("连载中", "已完结", "短篇").withIndex()) {
            for (r18 in listOf(false, true)) {
                val attentions = if(r18) listOf("残酷描写", "R18") else listOf("R15")
                val outline = WebOutline(providerId = ref.provider, novelId = ref.id, type = type, attentions = attentions).card()
                val detail = WebDetail(type = type, attentions = attentions).card(ref)
                val restored = appJson.decodeFromString<BookCard>(appJson.encodeToString(detail))
                val expected = if(r18) adult[index] else general[index]
                for (book in listOf(outline, detail, restored, detail.copy(ref = BookRef("kakuyomu", "other")))) {
                    assertEquals(expected, webCoverPalette(book))
                }
            }
        }
    }

    @Test fun metadataRefreshChangesClassificationWithoutChangingBookIdentity() {
        val old = WebDetail(type = "连载中", attentions = listOf("R18")).card(ref)
        val completed = WebDetail(type = "已完结").card(ref).withKnownUpdateTime(old)
        assertEquals(WebCoverPalette.GeneralCompleted, webCoverPalette(completed))
        assertEquals(old.ref, completed.ref)
        val partial = BookCard(ref, "新标题").withKnownUpdateTime(old)
        assertEquals(WebCoverPalette.AdultSerial, webCoverPalette(partial))
        assertEquals(WebCoverPalette.Unknown, webCoverPalette(BookCard(BookRef("other", "book"), "标题").withKnownUpdateTime(old)))
    }

    @Test fun sexualContentUsesTheSitesAdultClassificationAcrossListsDetailsAndExistingShelves() {
        // WebNovelEsDataSource 的 R18 筛选包含 attentions 中的 R18 或性描写。
        // Kakuyomu、Novelup 的性描写不会额外带 R18，不能将它们降为一般向。
        val palettes = listOf(WebCoverPalette.AdultSerial, WebCoverPalette.AdultCompleted, WebCoverPalette.AdultShort)
        for(provider in listOf("kakuyomu", "novelup")) {
            val novelRef = BookRef(provider, "story")
            for((index, type) in listOf("连载中", "已完结", "短篇").withIndex()) {
                val outline = appJson.decodeFromString<WebOutline>("""{"providerId":"$provider","novelId":"story","type":"$type","attentions":["暴力描写","性描写"]}""").card()
                val detail = appJson.decodeFromString<WebDetail>("""{"type":"$type","attentions":["性描写"]}""").card(novelRef)
                val shelf = appJson.decodeFromString<LibraryState>("""{"books":[{"book":{"ref":{"provider":"$provider","id":"story"},"title":"旧书","novelType":"$type","attentions":["性描写"]}}]}""")
                for(book in listOf(outline, detail, shelf.books.single().book)) {
                    assertEquals("$provider / $type", palettes[index], webCoverPalette(book))
                }
            }
        }
        val general = WebDetail(type = "连载中", attentions = listOf("R15", "暴力描写", "残酷描写"), keywords = listOf("性描写")).card(ref)
        assertEquals(WebCoverPalette.GeneralSerial, webCoverPalette(general))
    }

    @Test fun legacyBooksStayReadableAndLearnClassificationWithoutLogin() {
        val legacy = appJson.decodeFromString<BookCard>("""{"ref":{"provider":"syosetu","id":"story"},"title":"旧书"}""")
        assertEquals(WebCoverPalette.Unknown, webCoverPalette(legacy))
        val state = LibraryState(books = listOf(SavedBook(legacy)))
        val refreshed = state.withCloudReadingMetadata(listOf(WebDetail(type = "短篇", attentions = listOf("R18")).card(ref)), null)
        assertEquals(WebCoverPalette.AdultShort, webCoverPalette(refreshed.books.single().book))
        assertEquals(state.positions, refreshed.positions)
    }

    @Test fun classificationDoesNotGuessFromTitleOrKeywordsAndPreservesCloudAccountIsolation() {
        val book = WebDetail(type = "连载中", keywords = listOf("R18"), titleJp = "R18 标题").card(ref)
        assertEquals(WebCoverPalette.GeneralSerial, webCoverPalette(book))
        assertEquals(WebCoverPalette.AdultSerial, webCoverPalette(book.copy(attentions = listOf(" r-18 "))))
        assertEquals(WebCoverPalette.Unknown, webCoverPalette(book.copy(novelType = "未知类型")))
        val reading = CloudReadingProgress("alice", chapterId = "5")
        val state = LibraryState(books = listOf(SavedBook(book.copy(cloudReading = reading))))
        val incoming = book.copy(cloudReading = CloudReadingProgress("bob", chapterId = "9"))
        assertEquals(reading, state.withCloudReadingMetadata(listOf(incoming), "alice").books.single().book.cloudReading)
    }
}
