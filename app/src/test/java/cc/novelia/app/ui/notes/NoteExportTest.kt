package cc.novelia.app.ui.notes

import cc.novelia.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class NoteExportTest {
    private val highlight = Note("one", "local/one", "chapter", 4, "喜欢的句子\n第二行", "", bookTitle = "书名", chapterTitle = "第一章",
        bookmarked = false, selection = NoteSelection("gpt", start = 0, end = 10))

    @Test fun selectionOnlyRecordsAppearAndExportOnlyTheFilteredBook() {
        val state = LibraryState(notes = listOf(highlight, highlight.copy(id = "other", key = "local/two", quote = "不应导出")))
        val notes = presentNotes(state, "喜欢", "local/one")
        assertEquals(listOf(highlight), notes.map { it.note })
        val exported = exportNotes(notes, NoteExportFormat.Text)
        assertTrue(exported.contains("喜欢的句子\n第二行"))
        assertTrue(exported.contains("第一章 · 第 5 段"))
        assertTrue(exported.contains("划线 · GPT 译文"))
        assertFalse(exported.contains("不应导出"))
    }

    @Test fun markdownEscapesTextAndKeepsMultilineQuotesAndNotes() {
        val note = highlight.copy(quote = "<img>\n# 标题", text = "**批注** [链接](地址)")
        val output = exportNotes(presentNotes(LibraryState(notes = listOf(note))), NoteExportFormat.Markdown)
        assertTrue(output.contains("> \\<img\\>\n> \\# 标题"))
        assertTrue(output.contains("\\*\\*批注\\*\\* \\[链接\\](地址)"))
        assertTrue(output.contains("共 1 条"))
    }

    @Test fun identicalBookTitlesRemainSeparateAndInvisibleEmptyRecordsAreExcluded() {
        val state = LibraryState(notes = listOf(highlight, highlight.copy(id = "two", key = "local/two"),
            highlight.copy(id = "empty", selection = null)))
        val output = exportNotes(presentNotes(state), NoteExportFormat.Markdown)
        assertEquals(2, Regex("## 书名").findAll(output).count())
        assertTrue(output.contains("共 2 条"))
    }
}
