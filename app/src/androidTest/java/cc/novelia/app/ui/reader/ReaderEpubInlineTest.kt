package cc.novelia.app.ui.reader

import android.graphics.Typeface
import android.graphics.Bitmap
import android.text.Spanned
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import cc.novelia.app.data.model.*
import cc.novelia.app.reader.*
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderEpubInlineTest {
    @get:Rule val compose = createComposeRule()
    private val body = "東京[1]へ行く。漢字を読みます。"
    private val note = "东京：日本的首都。脚注可以直接查看并关闭。"
    private val part = TextPart(body, "日文", inline = listOf(
        InlineContent(0, 2, InlineKind.Ruby, "とうきょう"),
        InlineContent(2, 5, InlineKind.Note, note),
        InlineContent(9, 11, InlineKind.Bold),
        InlineContent(9, 11, InlineKind.Ruby, "かんじ"),
        InlineContent(12, 14, InlineKind.Italic),
        InlineContent(15, 17, InlineKind.Underline)
    ))
    private val paragraph = ReadingParagraph(0, listOf(part))
    private val settings = ReaderSettings(mode = "jp", fontSize = 22f, indent = false, lineHeight = 1f, tapPageTurn = true)

    @Test fun nativeLayoutHonorsAllIndependentDisplaySwitchesWithoutChangingText() {
        for (ruby in listOf(false, true)) for (notes in listOf(false, true)) for (styles in listOf(false, true)) {
            val configured = settings.copy(showEpubRuby = ruby, epubFootnotePopup = notes, showEpubStyles = styles)
            val layout = measureEInkChapter(listOf(paragraph), configured, 300, 300, 1f, 1f).layouts.getValue(0)
            val text = layout.text as Spanned
            assertEquals(body, text.toString())
            assertEquals(if (ruby) 2 else 0, text.getSpans(0, text.length, ReaderRubySpan::class.java).size)
            assertEquals(if (notes) 1 else 0, text.getSpans(0, text.length, ReaderNoteSpan::class.java).size)
            assertEquals(if (styles) 2 else 0, text.getSpans(0, text.length, android.text.style.StyleSpan::class.java).size)
        }
    }

    @Test fun readingPreferencesExposeIndependentEpubSwitchesAndKeepThemOnReopen() {
        var selected by mutableStateOf(ReaderSettings())
        var open by mutableStateOf(true)
        compose.setContent { AppInteractionMode(eInk = true, reducedMotion = true) { NoveliaTheme("light") {
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (open) ReaderPreferences(selected, perBook = true, defaultSettings = ReaderSettings()) { selected = it }
            }
        } } }
        fun openGroup() {
            compose.onNodeWithText("更多").performClick()
            compose.onNodeWithText("EPUB 附加显示").performScrollTo().performClick()
        }
        openGroup()
        compose.onNodeWithText("显示原书强调样式").performScrollTo().assertIsDisplayed()
        capture(compose.onRoot(), "epub-preferences.png")
        compose.onNodeWithText("显示假名注音").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(selected.showEpubRuby); assertTrue(selected.epubFootnotePopup && selected.showEpubStyles) }
        compose.onNodeWithText("点击脚注弹窗").performScrollTo().performClick()
        compose.onNodeWithText("显示原书强调样式").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(selected.epubFootnotePopup || selected.showEpubStyles); open = false }
        compose.runOnIdle { open = true }
        openGroup()
        compose.runOnIdle { assertFalse(selected.showEpubRuby || selected.epubFootnotePopup || selected.showEpubStyles) }
        compose.onNodeWithText("显示假名注音").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(selected.showEpubRuby); assertFalse(selected.epubFootnotePopup || selected.showEpubStyles) }
    }

    @Test fun rubyReservesHeightAndPagesCoverEveryBaseCharacterAtDifferentSizes() {
        for (fontSize in listOf(16f, 32f)) for (scale in listOf(1f, 1.5f)) {
            val typography = settings.copy(fontSize = fontSize, lineHeight = .5f)
            val rich = measureEInkChapter(listOf(paragraph), typography, 160, 90, 1f, scale)
            val plain = measureEInkChapter(listOf(paragraph.copy(parts = listOf(part.copy(inline = emptyList())))), typography, 160, 90, 1f, scale)
            val layout = rich.layouts.getValue(0)
            assertTrue(layout.getLineBottom(0) > plain.layouts.getValue(0).getLineBottom(0))
            assertEquals(body, rich.pages.flatMap { it.lines }.joinToString("") { body.substring(it.start, it.end) })
            assertEquals(body, layout.text.toString())
            val spans = layout.text as Spanned
            assertEquals(2, spans.getSpans(0, body.length, ReaderRubySpan::class.java).size)
            val path = android.graphics.Path()
            layout.readerSelectionPath(1, 2, path)
            val bounds = android.graphics.RectF()
            path.computeBounds(bounds, true)
            assertTrue("单字搜索应覆盖注音底文组", bounds.width() > 0)
            for (line in 1 until layout.lineCount) assertTrue(layout.getLineTop(line) >= layout.getLineBottom(line - 1))
        }
    }

    @Test fun noteHitTestingRejectsTrailingWhitespaceAndOtherLines() {
        val layout = measureEInkChapter(listOf(paragraph), settings, 300, 400, 1f, 1f, Typeface.DEFAULT).layouts.getValue(0)
        val x = (layout.getPrimaryHorizontal(2) + layout.getPrimaryHorizontal(5)) / 2
        val y = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        assertEquals(note, layout.noteAt(x, y))
        assertNull(layout.noteAt(-1f, y))
        assertNull(layout.noteAt(299f, layout.height - 1f))
        assertNull(layout.noteAt(x, -1f))
        assertNull(layout.noteAt(x, layout.height + 1f))
    }

    @Test fun bothModesOpenFootnotesWithoutTurningPagesAndLongPressStillSelects() {
        var paged by mutableStateOf(false)
        var displayed by mutableStateOf(settings)
        var popup by mutableStateOf<String?>(null)
        var menus = 0
        var turns = 0
        var selections = 0
        val state = EInkPageState(null)
        compose.setContent {
            AppInteractionMode(eInk = true, reducedMotion = true) { NoveliaTheme("light") {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.fillMaxSize().background(Color.White).padding(start = 32.dp, top = 180.dp)) {
                        if (paged) EInkPage(listOf(paragraph), displayed, state, Modifier.requiredSize(300.dp, 300.dp),
                            imageModel = { null }, onToggleMenu = { menus++ }, onSelect = { selections++ },
                            onPage = { turns++ }, onNote = { popup = it })
                        else {
                            val navigation = rememberReaderTapNavigation(true, onToggleMenu = { menus++ }, onPage = { turns++ })
                            Box(Modifier.requiredSize(300.dp, 300.dp).readerTapNavigation(navigation)) {
                                ReaderTextParagraph(paragraph, displayed, displayed, Color.Black, navigation::click, { selections++ }, null,
                                    onNote = { navigation.cancel(); popup = it }) { _, _ -> }
                            }
                        }
                        popup?.let { ReaderFootnoteDialog(it) { popup = null } }
                    }
                }
            } }
        }
        val layout = measureEInkChapter(listOf(paragraph), settings, 300, 300, 1f, 1f, Typeface.DEFAULT).layouts.getValue(0)
        val point = Offset((layout.getPrimaryHorizontal(2) + layout.getPrimaryHorizontal(5)) / 2,
            (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f)
        for (mode in listOf(false, true)) {
            compose.runOnIdle { paged = mode }
            if (mode) compose.waitUntil(10_000) { state.ready }
            val text = compose.onNodeWithText(body, useUnmergedTree = true)
            text.assertIsDisplayed()
            capture(text, if (mode) "epub-inline-paged.png" else "epub-inline-scroll.png")
            text.performTouchInput { click(point) }
            compose.onNodeWithText(note).assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, menus); assertEquals(0, turns); assertEquals(0, state.pageIndex) }
            saveTestScreenshot(if (mode) "epub-footnote-paged.png" else "epub-footnote-scroll.png")
            compose.onNodeWithText("关闭").performClick()
            text.performTouchInput { longClick(point) }
            compose.runOnIdle { assertNull(popup); assertEquals(if (mode) 2 else 1, selections) }
            compose.runOnIdle { displayed = settings.copy(epubFootnotePopup = false) }
            compose.waitForIdle()
            if (mode) compose.waitUntil(10_000) { state.ready }
            text.performTouchInput { click(point) }
            compose.waitUntil(3_000) { menus + turns > 0 }
            compose.runOnIdle { assertNull(popup); displayed = settings; menus = 0; turns = 0 }
        }
    }

    @Test fun footnoteOnLaterPageUsesTheVisibleSliceCoordinates() {
        val prefix = "前の文章です。".repeat(60)
        val longPart = part.copy(text = prefix + part.text, inline = part.inline.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) })
        val content = listOf(ReadingParagraph(0, listOf(longPart)))
        val state = EInkPageState(null)
        var popup by mutableStateOf<String?>(null)
        var turns = 0
        compose.setContent { AppInteractionMode(eInk = true, reducedMotion = true) { NoveliaTheme("dark") {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(start = 32.dp, top = 180.dp)) {
                    EInkPage(content, settings, state, Modifier.requiredSize(300.dp, 130.dp), imageModel = { null },
                        onToggleMenu = {}, onSelect = {}, onPage = { turns++ }, onNote = { popup = it },
                        background = Color.Black, foreground = Color.White)
                    popup?.let { ReaderFootnoteDialog(it) { popup = null } }
                }
            }
        } } }
        compose.waitUntil(10_000) { state.ready }
        val target = prefix.length + 3
        compose.runOnIdle { state.find(0, target); assertTrue(state.pageIndex > 0) }
        val layout = measureEInkChapter(content, settings, 300, 130, 1f, 1f, Typeface.DEFAULT).layouts.getValue(0)
        val line = layout.getLineForOffset(target)
        val top = layout.getLineTop(layout.getLineForOffset(state.current!!.lines.first().start))
        val point = Offset((layout.getPrimaryHorizontal(target) + layout.getPrimaryHorizontal(target + 1)) / 2,
            (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f - top)
        val visible = compose.onNodeWithText("[1]", substring = true, useUnmergedTree = true)
        capture(visible, "epub-inline-dark-later-page.png")
        visible.performTouchInput { click(point) }
        compose.onNodeWithText(note).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, turns) }
    }

    private fun capture(node: SemanticsNodeInteraction, name: String) {
        compose.waitForIdle()
        val bitmap = node.captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
