package cc.novelia.app.reader

import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderEnhancementsTest {
    @Test fun settingsCompareEffectiveThemesAndRevealIndependentDifferences() {
        val defaults = ReaderSettings()
        val book = defaults.copy(fontSize = 24f, speechContinueChapters = false, speechNetworkContinuation = false, tapPageTurn = true)
        assertEquals(setOf("字号", "连续听书", "联网续章", "点击区域翻页"), readerSettingsDifferences(book, defaults).map { it.label }.toSet())
        assertEquals("24.0 sp", readerSettingsDifferences(book, defaults).first { it.label == "字号" }.book)
        assertTrue(readerSettingsDifferences(defaults.copy(monochrome = true), defaults.withTheme("monochrome")).isEmpty())
        assertTrue(readerSettingsDifferences(defaults, defaults).isEmpty())
    }

    @Test fun continuousSpeechSettingsMigrateAndRoundTrip() {
        val legacy = appJson.decodeFromString<ReaderSettings>("{}")
        assertTrue(legacy.speechContinueChapters)
        assertTrue(legacy.speechNetworkContinuation)
        val offline = legacy.copy(speechNetworkContinuation = false)
        assertEquals(offline, appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(offline)))
        assertFalse(offline.withEInkMode(true).withEInkMode(false).speechNetworkContinuation)
    }

    @Test fun multipleExcursionJumpsRetainTheOriginalChapterAndCharacter() {
        val original = ReadingReturnPoint(Position("original", 3, textOffset = 91), 7)
        val visit = ReadingReturnPoint(Position("searched", 12, textOffset = 430), 20)
        assertEquals(original, retainReadingReturnPoint(retainReadingReturnPoint(null, original), visit))
        assertEquals(original, appJson.decodeFromString<ReadingReturnPoint>(appJson.encodeToString(original)))
    }

    @Test fun returnAnchorUsesOriginalParagraphIdentityAfterProjectionChanges() {
        val origin = ReadingReturnPoint(Position("original", 2, offset = 420, textOffset = 91), 7)
        val paragraphs = listOf(1, 4, 7, 10).map { ReadingParagraph(it, listOf(TextPart("正文", "gpt"))) }
        val resolved = origin.resolvedPosition(paragraphs)
        assertEquals(3, resolved.index)
        assertEquals(91, resolved.textOffset)
        assertEquals(0, resolved.offset)
    }

    @Test fun illustrationAndTitleReturnAnchorsKeepPixelFallback() {
        val paragraphs = listOf(ReadingParagraph(7, emptyList(), imageUrl = "https://example.com/image.png"))
        val image = ReadingReturnPoint(Position("chapter", 1, offset = 380), 7).resolvedPosition(paragraphs)
        assertEquals(1, image.index)
        assertEquals(380, image.offset)
        val title = ReadingReturnPoint(Position("chapter", 0, offset = 20)).resolvedPosition(paragraphs)
        assertEquals(0, title.index)
        assertEquals(20, title.offset)
    }
}
