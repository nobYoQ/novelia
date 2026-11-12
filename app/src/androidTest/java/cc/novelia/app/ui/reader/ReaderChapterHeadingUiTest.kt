package cc.novelia.app.ui.reader

import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderChapterHeadingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headingWrapsAndBodyAnchorsSurviveReflowAndReopen() {
        val title = "第十二章　在漫长旅途的尽头再次相逢".repeat(8)
        val paragraphs = listOf(ReadingParagraph(7, listOf(TextPart("正文内容。".repeat(100), "gpt"))))
        val settings = ReaderSettings(fontSize = 20f)
        val measured = measureEInkChapter(paragraphs, settings, 220, 150, Density(1f), title = title)
        val headingLines = measured.pages.flatMap { it.lines }.filter { it.paragraph == CHAPTER_HEADING_PARAGRAPH }
        assertEquals(title, headingLines.joinToString("") { title.substring(it.start, it.end) })
        assertSame(paragraphs, measured.paragraphs)
        val state = EInkPageState(null)
        state.install(measured.pages, paragraphs)
        assertEquals(CHAPTER_HEADING_PARAGRAPH, state.paragraph)
        state.move(1)
        assertEquals(CHAPTER_HEADING_PARAGRAPH, state.paragraph)
        assertTrue(state.textOffset > 0)
        val savedTitle = Position("chapter", state.paragraph + 1, textOffset = state.textOffset)
        val reopened = EInkPageState(savedTitle)
        reopened.install(measured.pages, paragraphs)
        assertEquals(state.pageIndex, reopened.pageIndex)

        state.find(0, 120, 7)
        val reflow = measureEInkChapter(paragraphs, settings, 280, 190, Density(1f), title = title)
        state.install(reflow.pages, paragraphs)
        assertTrue(state.current!!.lines.any { it.paragraph == 0 && it.start <= 120 && it.end > 120 })
        assertEquals(7, state.sourceIndex)
        assertEquals(120, state.anchor.textOffset)
        assertEquals(7, state.anchor.sourceIndex)
        state.find(CHAPTER_HEADING_PARAGRAPH)
        assertEquals(0, state.pageIndex)
    }

    @Test fun anExistingLocalHeadingIsNotDuplicatedAndImagesKeepTheirOwnPage() {
        val settings = ReaderSettings()
        val local = listOf(ReadingParagraph(0, listOf(TextPart("第一章　启程", "local"))))
        val measured = measureEInkChapter(local, settings, 320, 400, Density(1f), title = "第一章 启程")
        assertFalse(CHAPTER_HEADING_PARAGRAPH in measured.layouts)
        val image = listOf(ReadingParagraph(0, emptyList(), localImageId = "cover"))
        val illustrated = measureEInkChapter(image, settings, 320, 400, Density(1f), title = "插图章")
        assertEquals(2, illustrated.pages.size)
        assertTrue(illustrated.pages.last().lines.single().image)
        assertEquals(0, illustrated.pages.last().lines.single().paragraph)
    }

    @Test fun pagedReaderShowsTitleOnEntryAndAgainAfterReturningToFirstPage() {
        val title = "第十二章　在漫长旅途尽头再次相逢"
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("这一段是小说正文。".repeat(160), "gpt"))))
        val state = EInkPageState(null)
        compose.setContent { NoveliaTheme("light") {
            EInkPage(paragraphs, ReaderSettings(eInkMode = true), state, Modifier.size(300.dp, 300.dp),
                imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move, chapterTitle = title)
        } }
        compose.waitUntil(10_000) { state.ready }
        compose.onNodeWithTag("reader-page-chapter-title").assertIsDisplayed().assertTextEquals(title)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = java.io.File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "reader-chapter-heading.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.runOnIdle { state.move(1) }
        compose.onNodeWithTag("reader-page-chapter-title").assertDoesNotExist()
        compose.runOnIdle { state.move(-1) }
        compose.onNodeWithTag("reader-page-chapter-title").assertIsDisplayed()
    }
}
