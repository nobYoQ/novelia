package cc.novelia.app.ui.reader

import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderSpacingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lineHeightCanReachHalfInBothModesAndSurvivesReopeningPreferences() {
        var settings by mutableStateOf(ReaderSettings())
        var showing by mutableStateOf(true)
        compose.setContent { AppInteractionMode(eInk = settings.eInkMode, reducedMotion = true) { NoveliaTheme("light") {
            if(showing) ReaderPreferences(settings) { settings = it }
        } } }
        compose.onNodeWithTag("reader-line-height").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        compose.runOnIdle { assertEquals(.5f, settings.lineHeight, 0f) }
        compose.onNodeWithText("行距 0.5").assertIsDisplayed()
        compose.runOnIdle { settings = settings.withEInkMode(true).copy(lineHeight = .55f) }
        compose.onNodeWithContentDescription("减小 行距", substring = true).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(.5f, settings.lineHeight, .0001f) }
        compose.onNodeWithContentDescription("减小 行距", substring = true).assertIsNotEnabled()
        compose.runOnIdle { showing = false }
        compose.runOnIdle { showing = true }
        compose.onNodeWithText("行距 0.5").assertIsDisplayed()
    }

    @Test fun chineseScrollParagraphsUseContentHeightAndTheSelectedGapWithoutJapaneseSlots() {
        var settings by mutableStateOf(ReaderSettings(mode = "zh", fontSize = 14f, lineHeight = 1f, paragraphSpacing = 0f, indent = false))
        val chapter = Chapter(paragraphs = listOf("隠された日本語一", "隠された日本語二", "隠された日本語三"),
            sakuraParagraphs = listOf("放心吧，他应该不会伤害我。", "虽然没有证据，但本能这么告诉我。", "那么，我也不必忍耐了吧？"))
        val paragraphs = prepareReadingParagraphs(chapter, settings)
        compose.setContent {
            NoveliaTheme("dark") {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Surface(Modifier.requiredWidth(327.dp).testTag("spacing-preview")) {
                        Column(verticalArrangement = Arrangement.spacedBy(settings.resolvedParagraphSpacing.dp)) {
                            paragraphs.forEach { ReaderTextParagraph(it, settings, settings, Color.White, {}, {}, null) { _, _ -> } }
                        }
                    }
                }
            }
        }
        fun bounds(index: Int) = compose.onNodeWithText(chapter.sakuraParagraphs!![index], useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("正文不应强制保留 48dp 高度", bounds(0).height < 48f)
        assertEquals(0f, bounds(1).top - bounds(0).bottom, 1f)
        compose.onNodeWithText("隠された日本語", substring = true).assertDoesNotExist()
        capture("spacing-preview", "reader-spacing-zero.png")
        compose.runOnIdle { settings = settings.copy(paragraphSpacing = 20f) }
        assertEquals(20f, bounds(1).top - bounds(0).bottom, 1f)
    }

    @Test fun halfLineHeightOverlapsGlyphsAndMeasureCandidateLowerBounds() {
        val sample = "国語中文的字形"
        val paragraph = ReadingParagraph(0, listOf(TextPart(List(3) { sample }.joinToString("\n"), "日文")))
        var settings by mutableStateOf(ReaderSettings(mode = "jp", fontSize = 14f, lineHeight = 1f, indent = false))
        compose.setContent {
            NoveliaTheme("light") {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Column(Modifier.requiredWidth(327.dp)) {
                        ReaderTextParagraph(paragraph, settings, settings, Color.Black, {}, {}, null) { _, _ -> }
                    }
                }
            }
        }
        val results = mutableListOf("fontSize,multiplier,glyphHeight,scrollBaselineGap,pageBaselineGap")
        for(fontSize in listOf(14f, 19f, 32f)) for(multiplier in listOf(.5f, .8f, .9f, 1f)) {
            compose.runOnIdle { settings = settings.copy(fontSize = fontSize, lineHeight = multiplier) }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(paragraph.parts.single().text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val scrollGap = layouts.single().getLineBaseline(1) - layouts.single().getLineBaseline(0)
            val layout = measureEInkChapter(listOf(paragraph), settings, 327, 500, 1f, 1f).layouts.getValue(0)
            val bounds = Rect()
            layout.paint.getTextBounds(sample, 0, sample.length, bounds)
            val pageGap = layout.getLineBaseline(1) - layout.getLineBaseline(0)
            results += "$fontSize,$multiplier,${bounds.height()},$scrollGap,$pageGap"
            if(multiplier == .5f) {
                assertTrue("0.5 倍在滚动排版中会重叠", scrollGap < bounds.height())
                assertTrue("0.5 倍在分页排版中会重叠", pageGap < bounds.height())
            }
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "reader-line-height-probe.csv")
            .writeText(results.joinToString("\n"))
    }

    @Test fun changingParagraphSpacingReflowsStaticPagesAndChangesTheRenderedGap() {
        var settings by mutableStateOf(ReaderSettings(fontSize = 14f, lineHeight = 1f, paragraphSpacing = 20f, indent = false, eInkMode = true))
        val paragraphs = (1..30).map { ReadingParagraph(it - 1, listOf(TextPart("正文第 $it 段", "sakura"))) }
        val state = EInkPageState(null)
        compose.setContent {
            NoveliaTheme("light") {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    EInkPage(paragraphs, settings, state, Modifier.requiredWidth(327.dp).height(200.dp),
                        imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
                }
            }
        }
        compose.waitUntil(10_000) { state.ready }
        val before = state.pages
        fun gap(): Float {
            val first = compose.onNodeWithText("正文第 1 段", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithText("正文第 2 段", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            return second.top - first.bottom
        }
        assertEquals(20f, gap(), 1f)
        compose.runOnIdle { settings = settings.copy(paragraphSpacing = 0f) }
        compose.waitUntil(10_000) { state.ready && state.pages !== before }
        assertEquals(0f, gap(), 1f)
        assertTrue(state.pages.size < before.size)
        assertEquals(30, state.pages.sumOf { it.lines.size })
    }

    @Test fun paragraphSpacingPreferenceCanReachZeroInEInkMode() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { AppInteractionMode(eInk = true, reducedMotion = true) { NoveliaTheme("light") { ReaderPreferences(settings) { settings = it } } } }
        compose.onNodeWithTag("reader-paragraph-spacing").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(0f, settings.paragraphSpacing, 0f) }
    }

    private fun capture(tag: String, name: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
