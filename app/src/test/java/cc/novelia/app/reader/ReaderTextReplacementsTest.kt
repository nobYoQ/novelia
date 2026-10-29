package cc.novelia.app.reader

import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ReaderTextReplacementsTest {
    @Test fun longestLiteralMatchWinsWithoutRecursiveOrRegexReplacement() {
        val replacer = ReaderTextReplacer(mapOf("爱丽丝" to "艾莉丝", "爱丽" to "小爱", "艾莉丝" to "不能连替", ".*" to "符号", "😀" to "笑脸"))
        assertEquals("艾莉丝和小爱，符号笑脸", replacer.replace("爱丽丝和爱丽，.*😀"))
        assertEquals("aA", ReaderTextReplacer(mapOf("AA" to "B")).replace("aA"))
        assertNull(ReaderTextReplacer(mapOf("a" to "long")).replace("aaa", maxCharacters = 5))
        var checks = 0
        assertThrows(IllegalStateException::class.java) { replacer.replace("爱丽丝".repeat(1000)) { if(++checks > 1) error("cancelled") } }
    }

    @Test fun everyReadingProjectionUsesTheSameReplacementsAndKeepsImagesAndSources() = runTest {
        val chapter = Chapter(paragraphs = listOf("アリス。", "<图片>https://example.com/爱丽丝.png"), gptParagraphs = listOf("爱丽丝来了。"))
        val settings = ReaderSettings(mode = "jp-zh", engines = listOf("gpt", "sakura", "youdao"),
            textReplacements = mapOf("アリス" to "艾莉丝", "爱丽丝" to "艾莉丝"), speechLanguage = "zh")
        val paragraphs = prepareReadingParagraphs(chapter, settings)
        assertEquals(listOf("艾莉丝。", "艾莉丝来了。"), paragraphs.first().parts.map { it.text })
        assertEquals("https://example.com/爱丽丝.png", paragraphs.last().imageUrl)
        assertEquals(listOf("日文", "gpt"), paragraphs.first().parts.map { it.source })
        val spoken = speechSegments(chapter, settings).single()
        assertEquals("艾莉丝来了。", spoken.text)
        assertEquals(ReadingTextMatch(0, 1, 0, 6), spoken.match(paragraphs))
        val results = searchBookText(listOf(TocItem(titleJp = "第一章", chapterId = "one")), "艾莉丝", settings, { chapter })
        assertEquals(2, results.matches.size)
        assertEquals(listOf("アリス。", "爱丽丝来了。"), prepareReadingParagraphs(chapter, settings.copy(textReplacementsEnabled = false)).first().parts.map { it.text })
        assertNotEquals(WebDavProjection.anchorSource(settings), WebDavProjection.anchorSource(settings.copy(textReplacementsEnabled = false)))
        assertEquals(settings, appJson.decodeFromString<ReaderSettings>(appJson.encodeToString(settings)))
    }

    @Test fun unchangedAnnotationsRelocateButChangedQuotesAreNotMislabelled() {
        val settings = ReaderSettings(textReplacements = mapOf("阿" to "阿莉西亚"))
        val paragraph = prepareReadingParagraphs(Chapter(paragraphs = listOf("阿说晚安。")), settings).single()
        val unchanged = Note("one", "book", "chapter", 0, "晚安", "", selection = NoteSelection("original", start = 2, end = 4))
        assertEquals(ReadingTextMatch(0, 0, 5, 7), noteMatch(unchanged, paragraph, 0))
        val changed = unchanged.copy(quote = "阿说", selection = NoteSelection("original", start = 0, end = 2))
        assertNull(noteMatch(changed, paragraph, 0))
        assertTrue(ReaderSettings().validTextReplacements)
        assertFalse(ReaderSettings(textReplacements = mapOf("" to "x")).validTextReplacements)
        assertFalse(ReaderSettings(textReplacements = mapOf("x" to "a".repeat(121))).validTextReplacements)
    }
}
