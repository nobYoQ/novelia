package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class SpeechSegmentsTest {
    @Test fun repeatedSentencesKeepTheirOwnOffsetsAcrossLanguageOrderChanges() {
        val chapter = Chapter(paragraphs = listOf("原文。原文。"), gptParagraphs = listOf("译文。译文。"))
        val settings = ReaderSettings(mode = "jp-zh", speechLanguage = "zh", engines = listOf("gpt", "sakura", "youdao"))
        val queue = speechSegments(chapter, settings)
        assertEquals(listOf(0, 3), queue.map { it.start })
        val shown = prepareReadingParagraphs(chapter, settings)
        assertEquals(ReadingTextMatch(0, 1, 3, 6), queue.last().match(shown))
        val reversed = prepareReadingParagraphs(chapter, settings.copy(mode = "zh-jp"))
        assertEquals(ReadingTextMatch(0, 0, 3, 6), queue.last().match(reversed))
        assertNull(queue.last().match(prepareReadingParagraphs(chapter, settings.copy(mode = "jp"))))
    }

    @Test fun selectionTrimmingAndLongUnicodeChunksPreserveOriginalOffsets() {
        val selection = "  选中内容。" + "😀".repeat(2500) + "  "
        val queue = speechSelection(selection, 7, "original", start = 13)
        assertEquals(15, queue.first().start)
        assertEquals(selection.trim(), queue.joinToString("") { it.text })
        queue.forEach {
            assertEquals(it.text, selection.substring(it.start - 13, it.start - 13 + it.text.length))
            assertTrue(it.text.length <= 3500)
            assertFalse(it.text.last().isHighSurrogate())
        }
        assertEquals(queue, appJson.decodeFromString<List<SpeechSegment>>(appJson.encodeToString(queue)))
    }

    @Test fun sourceParagraphsSurviveEmptyParagraphsAndIllustrations() {
        val chapter = Chapter(paragraphs = listOf("", "<图片>https://example.com/a.png", "日本語。", "次。"), gptParagraphs = listOf("", "", "中文。", "下一段。"))
        assertEquals(listOf(2, 3), speechSegments(chapter, ReaderSettings(speechLanguage = "zh")).map { it.paragraph })
        assertEquals(listOf("次。"), speechSegments(chapter, ReaderSettings(speechLanguage = "jp"), 3).map { it.text })
    }

    @Test fun followingIsConfinedToTheSameBookAndActiveReadingSurface() {
        val playing = SpeechPlayback("session", "local/book", "chapter", null, 0, 2, true)
        assertTrue(playing.canFollow("local/book", resumed = true, blocked = false))
        assertFalse(playing.canFollow("local/other", true, false))
        assertFalse(playing.canFollow("local/book", false, false))
        assertFalse(playing.canFollow("local/book", true, true))
        assertFalse(playing.copy(playing = false).canFollow("local/book", true, false))
        assertFalse(playing.copy(following = false).canFollow("local/book", true, false))
    }
}
