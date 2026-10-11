package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import org.junit.Assert.*
import org.junit.Test

class ReaderProjectionTest {
    @Test fun bilingualOrderChangesKeepJapaneseSecondaryAndChinesePrimary() {
        val chapter = Chapter(paragraphs = listOf("日本語", "未翻訳"), sakuraParagraphs = listOf("中文"), gptParagraphs = listOf("另一译文"))
        for(parallel in listOf(false, true)) {
            val chineseFirst = projectParagraphs(chapter, ReaderSettings(mode = "zh-jp", parallel = parallel))
            val japaneseFirst = projectParagraphs(chapter, ReaderSettings(mode = "jp-zh", parallel = parallel))
            assertEquals("日文", japaneseFirst.first().parts.first().source)
            assertEquals("日文", chineseFirst.first().parts.last().source)
            for(paragraphs in listOf(chineseFirst, japaneseFirst)) {
                assertEquals(listOf(0, 1), paragraphs.map { it.index })
                assertTrue(paragraphs.first().parts.single { it.source == "日文" }.secondary)
                assertTrue(paragraphs.first().parts.filter { it.source != "日文" }.none { it.secondary })
                assertFalse(paragraphs.last().parts.single().secondary)
                assertEquals("未翻訳", paragraphs.last().parts.single().text)
            }
        }
    }

    @Test fun searchAdvancesThroughProjectedOffsetsWhenBlankSourceParagraphsWereRemoved() {
        val paragraphs = projectParagraphs(
            Chapter(paragraphs = listOf("", "目标一", "", "目标二", "", "目标三")),
            ReaderSettings(mode = "jp")
        )
        assertEquals(listOf(1, 3, 5), paragraphs.map { it.index })
        assertEquals(0, findNextReadingParagraph(paragraphs, "目标", -1))
        assertEquals(1, findNextReadingParagraph(paragraphs, "目标", 0))
        assertEquals(2, findNextReadingParagraph(paragraphs, "目标", 1))
        assertEquals(0, findNextReadingParagraph(paragraphs, "目标", 2))
        assertEquals(0, findNextReadingParagraph(paragraphs, "目标", 99))
    }

    @Test fun searchRejectsBlankInputAndSupportsTrimmedCaseInsensitiveQueries() {
        val paragraphs = projectParagraphs(Chapter(paragraphs = listOf("Alpha", "beta")), ReaderSettings(mode = "jp"))
        assertEquals(-1, findNextReadingParagraph(paragraphs, "  ", -1))
        assertEquals(-1, findNextReadingParagraph(emptyList(), "Alpha", -1))
        assertEquals(-1, findNextReadingParagraph(paragraphs, "missing", 0))
        assertEquals(0, findNextReadingParagraph(paragraphs, " ALPHA ", 0))
    }

    @Test fun traditionalPreparationConvertsOnlyTranslationsAndPreservesOriginalText() {
        val chapter = Chapter(paragraphs = listOf(" 简体原文 ", " 缺少译文 "), sakuraParagraphs = listOf(" 阅读龙 "))
        val paragraphs = prepareReadingParagraphs(chapter, ReaderSettings(mode = "zh-jp", traditional = true))
        assertEquals("閱讀龍", paragraphs[0].parts[0].text)
        assertEquals("简体原文", paragraphs[0].parts[1].text)
        assertTrue(paragraphs[0].parts[1].secondary)
        assertEquals("缺少译文", paragraphs[1].parts.single().text)
        assertTrue(paragraphs[1].fallback)
        assertEquals(" 阅读龙 ", chapter.sakuraParagraphs!!.single())
    }

    @Test fun localIllustrationsRemainImagesInEveryLanguageModeAndAreNotSearchResults() {
        val imageId = "a".repeat(64)
        val marker = "novelia-image:$imageId"
        for(mode in listOf("zh", "jp", "zh-jp", "jp-zh")) {
            val paragraphs = prepareReadingParagraphs(Chapter(paragraphs = listOf(marker, "正文")), ReaderSettings(mode = mode, traditional = true))
            assertEquals(imageId, paragraphs.first().localImageId)
            assertEquals(marker, paragraphs.first().parts.single().text)
            assertFalse(paragraphs.first().fallback)
            assertEquals(-1, findNextReadingParagraph(paragraphs, "novelia-image", -1))
        }
    }

    @Test fun illustrationMarkersRequireValidUrlsAndExactLocalHashes() {
        val paragraphs = projectParagraphs(
            Chapter(paragraphs = listOf("<图片>javascript:alert(1)", "<图片>https://example.org/image.png", "novelia-image:../../image")),
            ReaderSettings(mode = "jp")
        )
        assertNull(paragraphs[0].imageUrl)
        assertEquals("插图地址不可用", paragraphs[0].parts.single().text)
        assertEquals("https://example.org/image.png", paragraphs[1].imageUrl)
        assertNull(paragraphs[2].localImageId)
    }

    @Test fun translationPrioritySkipsMissingEntriesAndDoesNotDuplicateEngines() {
        val chapter = Chapter(paragraphs = listOf("原文"), sakuraParagraphs = listOf(""), gptParagraphs = listOf("GPT"), youdaoParagraphs = listOf("有道"))
        val settings = ReaderSettings(engines = listOf("missing", "sakura", "gpt", "gpt", "youdao"))
        assertEquals(listOf("GPT"), projectParagraphs(chapter, settings).single().parts.map { it.text })
        assertEquals(listOf("GPT", "有道"), projectParagraphs(chapter, settings.copy(parallel = true)).single().parts.map { it.text })
        assertEquals(listOf("原文"), projectParagraphs(chapter, settings.copy(mode = "jp")).single().parts.map { it.text })
    }
}
