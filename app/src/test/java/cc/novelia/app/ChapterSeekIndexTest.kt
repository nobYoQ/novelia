package cc.novelia.app

import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import org.junit.Assert.*
import org.junit.Test

class ChapterSeekIndexTest {
    private val settings = ReaderSettings(indent = false)

    @Test fun longParagraphsCanBeScrubbedWithinTheirTextInsteadOfJumpingOnlyBetweenParagraphs() {
        val index = ChapterSeekIndex(listOf(
            ReadingParagraph(4, listOf(TextPart("a".repeat(900), "jp"))),
            ReadingParagraph(8, listOf(TextPart("b".repeat(100), "jp")))
        ), settings)
        assertEquals(ChapterSeekAnchor(0, 500), index.anchorAt(.5f))
        assertEquals(ChapterSeekAnchor(1, 50), index.anchorAt(.95f))
        assertEquals(.5f, index.fractionAt(0, 500), .001f)
        assertEquals(.95f, index.fractionAt(1, 50), .001f)
    }

    @Test fun bilingualOffsetsIncludeEngineLabelsIndentAndPartSeparators() {
        val paragraph = ReadingParagraph(12, listOf(TextPart("abc", "jp"), TextPart("译文", "gpt")))
        val style = settings.copy(indent = true, parallel = true)
        val index = ChapterSeekIndex(listOf(paragraph), style)
        // 2 + 3 + newline + GPT/newline + 2 + 2 = 14 characters.
        assertEquals(ChapterSeekAnchor(0, 7), index.anchorAt(.5f))
        assertEquals(1f, index.fractionAt(0, 14), 0f)
    }

    @Test fun picturesAndEmptyChaptersHaveSafeSelectablePositions() {
        val image = ReadingParagraph(2, emptyList(), imageUrl = "https://example.com/image.png")
        val index = ChapterSeekIndex(listOf(image, ReadingParagraph(3, listOf(TextPart("a".repeat(400), "jp")))), settings)
        assertEquals(0, index.anchorAt(.25f).paragraph)
        assertEquals(ChapterSeekAnchor(1, 0), index.anchorAt(.5f))
        assertEquals(ChapterSeekAnchor(0, 0), ChapterSeekIndex(emptyList(), settings).anchorAt(1f))
    }

    @Test fun endpointsAndInvalidInputNeverSelectAnotherChapterOrANonexistentPage() {
        val index = ChapterSeekIndex(listOf(ReadingParagraph(0, listOf(TextPart("abcd", "jp")))), settings)
        assertEquals(ChapterSeekAnchor(0, 0), index.anchorAt(Float.NaN))
        assertEquals(ChapterSeekAnchor(0, 0), index.anchorAt(-1f))
        assertEquals(ChapterSeekAnchor(0, 3), index.anchorAt(2f))
        assertEquals(0, chapterSeekPage(0f, 12))
        assertEquals(11, chapterSeekPage(1f, 12))
        assertEquals(0, chapterSeekPage(1f, 1))
        assertEquals(1, chapterSeekPage(.5f, 2))
        assertEquals(0, chapterSeekPage(Float.NaN, 0))
    }
}
