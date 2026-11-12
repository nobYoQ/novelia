package cc.novelia.app.ui.reader

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ReaderCustomFont
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.saveTestScreenshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ReaderFontTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scrollRichTextAndBothPagersKeepTheSameGlyphSizeWithSystemFontScaling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var scale by mutableFloatStateOf(1f)
        var settings by mutableStateOf(ReaderSettings(fontFamily = "monospace", indent = false, fontSize = 32f))
        var secondary by mutableStateOf(false)
        var textDensity: Density = Density(1f)
        var richLayout: android.text.StaticLayout? = null
        val sample = "MM"
        compose.setContent { NoveliaTheme("light") {
            val density = remember(scale) { Density(context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { fontScale = scale })) }
            textDensity = density
            CompositionLocalProvider(LocalDensity provides density) {
                Column(Modifier.width(320.dp)) {
                    Box(Modifier.testTag("font-plain")) {
                        ReaderTextParagraph(ReadingParagraph(0, listOf(TextPart(sample, "gpt", secondary = secondary))),
                            settings, settings, Color.Black, {}, {}, null) { _, _ -> }
                    }
                    ReaderInlineText(TextPart(sample, "gpt", secondary = secondary), settings, Color.Black,
                        {}, {}, {}, emptyList(), emptyList()) { layout, _ -> richLayout = layout }
                }
            }
        } }
        for(fontScale in listOf(1f, 1.3f, 2f)) for(size in listOf(32f, 64f)) for(isSecondary in listOf(false, true)) {
            compose.runOnIdle { scale = fontScale; settings = settings.copy(fontSize = size); secondary = isSecondary }
            val results = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(sample) and hasAnyAncestor(hasTestTag("font-plain")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val scrollWidth = results.single().getBoundingBox(0).width
            val native = measureEInkChapter(listOf(ReadingParagraph(0, listOf(TextPart(sample, "gpt", secondary = isSecondary)))),
                settings, 1000, 1000, textDensity, Typeface.MONOSPACE).layouts.getValue(0)
            assertEquals("分页字形应与滚动一致：$fontScale/$size/$isSecondary", scrollWidth,
                native.getPrimaryHorizontal(1) - native.getPrimaryHorizontal(0), 1f)
            assertEquals("EPUB 字形应与滚动一致", scrollWidth,
                richLayout!!.getPrimaryHorizontal(1) - richLayout!!.getPrimaryHorizontal(0), 1f)
            assertEquals(with(textDensity) { readerPartFontSize(settings, isSecondary).sp.toPx() }, richLayout!!.paint.textSize, .01f)
        }
    }

    @Test fun fontChoicePreviewsAndCanRestoreTheBookDefaultWithoutChangingOtherSettings() {
        val defaults = ReaderSettings(fontFamily = "serif", fontSize = 23f)
        var settings by mutableStateOf(defaults.copy(fontFamily = "monospace"))
        compose.setContent { NoveliaTheme("light") { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            ReaderPreferences(settings, perBook = true, defaultSettings = defaults) { settings = it }
        } } }
        saveTestScreenshot("reader-font-entry-light.png")
        compose.onNodeWithTag("reader-font-settings").performClick()
        compose.onNodeWithTag("reader-font-option-system").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("system", settings.fontFamily); assertEquals(23f, settings.fontSize) }
        compose.onNodeWithTag("reader-font-option-system").assertIsSelected()
        compose.onNodeWithTag("reader-font-preview-card").performScrollTo()
        compose.onNodeWithTag("reader-font-preview").assertIsDisplayed()
        saveTestScreenshot("reader-font-options.png")
        compose.onNodeWithContentDescription("恢复默认阅读字体").performClick()
        compose.runOnIdle { assertEquals(defaults, settings) }
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("reader-font-settings").assertTextContains("衬线")
    }

    @Test fun darkFontPanelHasAClearEntryPreviewAndImportAction() {
        var settings by mutableStateOf(ReaderSettings(fontFamily = "serif"))
        compose.setContent { NoveliaTheme("dark") { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            ReaderPreferences(settings) { settings = it }
        } } }
        compose.onNodeWithTag("reader-font-settings").assertHasClickAction().assertHeightIsAtLeast(72.dp)
        saveTestScreenshot("reader-font-entry-dark.png")
        compose.onNodeWithTag("reader-font-settings").performClick()
        compose.onNodeWithTag("reader-font-option-serif").assertIsSelected()
        compose.onNodeWithTag("reader-font-preview-card").performScrollTo()
        saveTestScreenshot("reader-font-panel-dark.png")
        compose.onNodeWithTag("reader-font-import").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("完成").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reader-font-dialog").assertDoesNotExist()
    }

    @Test fun largeTextAndLongImportedNamesKeepActionsReachableInEInkMode() {
        var settings by mutableStateOf(ReaderSettings(fontFamily = "custom",
            customFont = ReaderCustomFont("f".repeat(64), "这是一个很长的自定义中文阅读字体文件名称.ttf")))
        var open by mutableStateOf(true)
        compose.setContent { AppInteractionMode(eInk = true, reducedMotion = true) { NoveliaTheme("dark") {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                if(open) ReaderFontDialog(settings, defaults = ReaderSettings(), onDismiss = { open = false }) { settings = it }
            }
        } } }
        compose.onNodeWithTag("reader-font-option-custom-${"f".repeat(64)}").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("reader-font-import").performScrollTo().assertIsDisplayed()
        saveTestScreenshot("reader-font-panel-large-text.png")
        compose.onNodeWithContentDescription("恢复默认阅读字体").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ReaderSettings(), settings) }
        compose.onNodeWithText("完成").assertIsDisplayed().performClick()
        compose.runOnIdle { assertFalse(open) }
    }

    @Test fun scrollAndStaticLayoutsUseTheChosenTypefaceAndRepaginate() {
        val sample = "iiii WWWW mmmm 1234 ".repeat(60)
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart(sample, "gpt"))))
        var settings by mutableStateOf(ReaderSettings(fontFamily = "monospace", indent = false))
        val state = EInkPageState(null)
        compose.setContent { NoveliaTheme("light") { Column {
            ReaderTextParagraph(paragraphs.single(), settings, settings, Color.Black, {}, {}, null) { _, _ -> }
        } } }
        compose.waitUntil(5_000) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(sample, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            kotlin.math.abs(layout.getBoundingBox(0).width - layout.getBoundingBox(5).width) < .1f
        }
        val mono = measureEInkChapter(paragraphs, settings, 300, 300, Density(1f), Typeface.MONOSPACE)
        assertEquals(Typeface.MONOSPACE, mono.layouts.getValue(0).paint.typeface)
        state.install(mono.pages, paragraphs)
        state.move(1)
        val anchor = state.textOffset
        val serif = measureEInkChapter(paragraphs, settings.copy(fontFamily = "serif"), 300, 300, Density(1f), Typeface.SERIF)
        state.install(serif.pages, paragraphs)
        assertEquals(pageForAnchor(serif.pages, 0, anchor), state.pageIndex)
        val monoPaint = mono.layouts.getValue(0).paint
        val serifPaint = serif.layouts.getValue(0).paint
        assertEquals(monoPaint.measureText("iiii"), monoPaint.measureText("WWWW"), .1f)
        assertTrue(serifPaint.measureText("WWWW") > serifPaint.measureText("iiii") * 2)
    }

    @Test fun paginationInvalidatesWhenTheSelectedFontChanges() {
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("iiii WWWW mmmm ".repeat(300), "gpt"))))
        var settings by mutableStateOf(ReaderSettings(fontFamily = "monospace", indent = false))
        val state = EInkPageState(null)
        compose.setContent { NoveliaTheme("light") {
            EInkPage(paragraphs, settings, state, Modifier.width(300.dp).height(300.dp),
                imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = state::move)
        } }
        compose.waitUntil(10_000) { state.ready }
        // 等待异步字体就绪后的最终页表。
        compose.waitForIdle()
        lateinit var before: List<StaticPage>
        compose.runOnIdle { state.move(1); before = state.pages; settings = settings.copy(fontFamily = "serif") }
        compose.waitUntil(10_000) { state.ready && state.pages !== before }
        assertTrue(state.pages.isNotEmpty())
    }

    @Test fun fontImportCopiesValidFilesAndRejectsDamagedOrMissingFonts() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "font-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = root }
        try {
            val source = File("/system/fonts").listFiles()!!.first { it.extension == "ttf" }
            val imported = source.inputStream().use { ReaderFontStore.importStream(context, "测试字体.ttf", it) }
            val saved = File(root, "reader-fonts/${imported.id}.font")
            assertTrue(saved.isFile)
            assertEquals(source.length(), saved.length())
            assertNotNull(ReaderFontStore.load(context, ReaderSettings(fontFamily = "custom", customFont = imported)))
            // 再次导入同一个文件也必须成功，且不会生成第二份持久副本。
            val repeated = source.inputStream().use { ReaderFontStore.importStream(context, "测试字体.ttf", it) }
            assertEquals(imported, repeated)
            try {
                ReaderFontStore.importStream(context, "bad.ttf", ByteArrayInputStream("not a font".toByteArray()))
                fail("损坏文件不应导入")
            } catch(expected: IllegalArgumentException) { }
            assertEquals(listOf(saved.name), saved.parentFile!!.listFiles()!!.filter { it.extension == "font" }.map { it.name })
            assertNull(ReaderFontStore.load(context, ReaderSettings(fontFamily = "custom", customFont = ReaderCustomFont("f".repeat(64), "missing.ttf"))))
        } finally { root.deleteRecursively() }
    }
}
