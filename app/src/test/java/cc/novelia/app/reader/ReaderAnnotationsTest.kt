package cc.novelia.app.reader

import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderAnnotationsTest {
    private fun note(p: ReadingParagraph, part: Int, start: Int, end: Int) = Note("note", "local/book", "one", p.index,
        p.parts[part].text.substring(start, end), "批注", bookmarked = false, selection = selectionFor(p, part, start, end))

    @Test fun selectionTracksTheTranslationAcrossDisplayOrderAndIndentChanges() {
        val p = ReadingParagraph(7, listOf(TextPart("原文", "日文"), TextPart("前文 喜欢的句子 后文", "gpt")))
        val note = note(p, 1, 3, 8)
        val reordered = p.copy(parts = p.parts.reversed())
        assertEquals(ReadingTextMatch(2, 0, 3, 8), noteMatch(note, reordered, 2))
        assertNull(noteMatch(note, p.copy(parts = p.parts.take(1)), 2))
        assertNull(noteMatch(note, p.copy(index = 8), 2))
        val match = noteMatch(note, reordered, 2)!!
        assertEquals(5, match.textOffset(reordered, ReaderSettings(indent = true, parallel = false)))
        assertEquals(3, match.textOffset(reordered, ReaderSettings(indent = false, parallel = false)))
    }

    @Test fun localTranslationOccurrencesDoNotShareHighlights() {
        val p = ReadingParagraph(0, listOf(TextPart("同样文字", "译文"), TextPart("同样文字", "译文")))
        assertEquals(ReadingTextMatch(0, 1, 0, 2), noteMatch(note(p, 1, 0, 2), p, 0))
    }

    @Test fun revisedTextOnlyRelocatesAnUnambiguousQuote() {
        val p = ReadingParagraph(0, listOf(TextPart("前文摘录后文", "gpt")))
        val n = note(p, 0, 2, 4)
        assertEquals(ReadingTextMatch(0, 0, 4, 6), noteMatch(n, p.copy(parts = listOf(TextPart("新增前文摘录后文", "gpt"))), 0))
        assertNull(noteMatch(n, p.copy(parts = listOf(TextPart("摘录其他摘录", "gpt"))), 0))
        assertNull(noteMatch(n, p.copy(parts = listOf(TextPart("完全改写", "gpt"))), 0))
    }

    @Test fun versionsIncludeHiddenEnginesWithoutChangingTheReadersChoice() {
        val chapter = Chapter(paragraphs = listOf("", "日本語"), sakuraParagraphs = listOf("", "樱译"), gptParagraphs = listOf("", "GPT译文"))
        val settings = ReaderSettings(mode = "zh", parallel = false)
        val versions = paragraphVersions(chapter, 1, settings)!!
        assertEquals(1, versions.index)
        assertEquals(listOf("日本語", "樱译", "GPT译文"), versions.parts.map { it.text })
        assertEquals(listOf("樱译"), prepareReadingParagraphs(chapter, settings).single().parts.map { it.text })
    }

    @Test fun localVersionsUseReliableGroupsAndDoNotInventPairsForPlainText() {
        val content = LocalReadingContent(groups = listOf(LocalBilingualGroup(listOf(0), listOf(listOf(1), listOf(2)))))
        val chapter = Chapter(paragraphs = listOf("原文", "译文甲", "译文乙", "独立正文"), localContent = content)
        assertEquals(listOf("原文", "译文甲", "译文乙"), paragraphVersions(chapter, 2, ReaderSettings())!!.parts.map { it.text })
        assertEquals(listOf("独立正文"), paragraphVersions(chapter, 3, ReaderSettings())!!.parts.map { it.text })
    }

    @Test fun oldBookmarksDecodeAndTwoSelectionsInOneParagraphSurviveSerialization() {
        val old = appJson.decodeFromString<Note>("""{"id":"old","key":"local/book","chapterId":"one","paragraph":0,"quote":"原文","text":""}""")
        assertNull(old.selection)
        assertTrue(old.bookmarked)
        val p = ReadingParagraph(0, listOf(TextPart("春天和秋天", "gpt")))
        val state = LibraryState(notes = listOf(old, note(p, 0, 0, 2), note(p, 0, 3, 5).copy(id = "another")))
        assertEquals(state, appJson.decodeFromString<LibraryState>(appJson.encodeToString(state)))
        assertNull(selectionFor(p, 0, 3, 3))
        assertNull(selectionFor(p, 0, -1, 2))
        assertNull(selectionFor(p, 0, 0, 99))
    }
}
