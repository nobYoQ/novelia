package cc.novelia.app.reader

import cc.novelia.app.data.model.*
import cc.novelia.app.files.epub.parseEpubChapter
import org.junit.Assert.*
import org.junit.Test

class InlinePreparationTest {
    @Test fun displayPreferencesNeverMutateMetadataOrText() {
        val part = TextPart("東京1", "日文", inline = listOf(InlineContent(0, 2, InlineKind.Ruby, "とうきょう"),
            InlineContent(0, 2, InlineKind.Bold), InlineContent(2, 3, InlineKind.Note, "说明")))
        val hidden = part.withInlinePreferences(ReaderSettings(showEpubRuby = false, epubFootnotePopup = false, showEpubStyles = false))
        assertEquals(part.text, hidden.text)
        assertTrue(hidden.inline.isEmpty())
        assertEquals(3, part.inline.size)
        assertEquals(part, part.withInlinePreferences(ReaderSettings()))
        assertEquals(setOf(InlineKind.Bold, InlineKind.Note), part.withInlinePreferences(ReaderSettings(showEpubRuby = false)).inline.map { it.kind }.toSet())
    }
    @Test fun joinedBilingualPartsKeepTheirOwnOffsetsAndNoteBodies() {
        val chapter = parseEpubChapter("c", "<p lang='ja'><b>日本</b><br/><ruby>語<rt>ご</rt></ruby></p><p lang='zh'>中文</p>", null, { null })!!
        val paragraph = prepareReadingParagraphs(chapter.toReaderChapter(), ReaderSettings(mode = "zh-jp")).single()
        val jp = paragraph.parts.last()
        assertEquals("日本\n語", jp.text)
        assertEquals(listOf(InlineContent(0, 2, InlineKind.Bold), InlineContent(3, 4, InlineKind.Ruby, "ご")), jp.inline)
        assertTrue(paragraph.parts.first().inline.isEmpty())
    }

    @Test fun lengthChangingReplacementsShiftMarksAndInvalidateOnlyChangedRuby() {
        val chapter = Chapter(paragraphs = listOf("前東京後日本"), localContent = LocalReadingContent(inline = mapOf(0 to listOf(
            InlineContent(1, 3, InlineKind.Ruby, "とうきょう"), InlineContent(4, 6, InlineKind.Ruby, "にほん"),
            InlineContent(1, 6, InlineKind.Bold), InlineContent(3, 4, InlineKind.Note, "n")
        )), notes = mapOf("n" to "注释")))
        val settings = ReaderSettings(mode = "jp", textReplacementsEnabled = true, textReplacements = mapOf("前" to "很长的前", "日本" to "日本国"))
        val part = prepareReadingParagraphs(chapter, settings).single().parts.single()
        assertEquals("很长的前東京後日本国", part.text)
        assertEquals(listOf(InlineContent(4, 6, InlineKind.Ruby, "とうきょう")), part.inline.filter { it.kind == InlineKind.Ruby })
        assertEquals(InlineContent(6, 7, InlineKind.Note, "注释"), part.inline.first { it.kind == InlineKind.Note })
        assertEquals(InlineContent(4, 10, InlineKind.Bold), part.inline.first { it.kind == InlineKind.Bold })
    }

    @Test fun traditionalTextAndRangesAreConvertedTogether() {
        val chapter = Chapter(paragraphs = listOf("阅读龙"), localContent = LocalReadingContent(inline = mapOf(0 to listOf(
            InlineContent(0, 2, InlineKind.Bold), InlineContent(2, 3, InlineKind.Note, "n")
        )), notes = mapOf("n" to "说明")))
        val part = prepareReadingParagraphs(chapter, ReaderSettings(mode = "zh", traditional = true)).single().parts.single()
        assertEquals("閱讀龍", part.text)
        assertEquals(InlineContent(0, 2, InlineKind.Bold), part.inline.first())
        assertEquals(InlineContent(2, 3, InlineKind.Note, "说明"), part.inline.last())
    }

    @Test fun corruptedRangesAndUnresolvedNotesNeverReachRenderers() {
        val chapter = Chapter(paragraphs = listOf("𠀀正文"), localContent = LocalReadingContent(inline = mapOf(0 to listOf(
            InlineContent(1, 2, InlineKind.Ruby, "bad"), InlineContent(-1, 2, InlineKind.Bold),
            InlineContent(0, 999, InlineKind.Italic), InlineContent(0, 2, InlineKind.Note, "missing")
        ))))
        assertTrue(prepareReadingParagraphs(chapter, ReaderSettings()).single().parts.single().inline.isEmpty())
    }
}
