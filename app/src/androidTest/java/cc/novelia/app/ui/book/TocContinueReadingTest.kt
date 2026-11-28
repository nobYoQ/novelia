package cc.novelia.app.ui.book

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.EpubLinkTarget
import cc.novelia.app.data.model.EpubNavigationEntry
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.LocalReadingContent
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.ReaderTocPane
import cc.novelia.app.ui.reader.ReaderTocSnapshot
import cc.novelia.app.ui.reader.ReaderTocState
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.theme.LocalThemeWallpaper
import cc.novelia.app.ui.theme.themePaper
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TocContinueReadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun existingEpubMarksOnlyItsIllustratedSectionInBothDirectories() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val ref = BookRef("local", UUID.randomUUID().toString())
        val anchors = mapOf("art" to 1)
        val book = LocalDocument(ref.id, "旧版画集", "epub", listOf(
            LocalChapter("c", "前言", listOf("纯文字", "novelia-image:${"a".repeat(64)}"), LocalReadingContent(anchors = anchors))
        ), epubNavigation = listOf(
            EpubNavigationEntry("前言", EpubLinkTarget("c"), 0),
            EpubNavigationEntry("画页", EpubLinkTarget("c", "art"), 0),
        ))
        // 模拟仅保留旧版已导入正文的设备，没有 EPUB 原件。
        File(app.store.documentsDir, "${ref.id}.json").writeText(appJson.encodeToString(book), Charsets.UTF_8)
        var reader by mutableStateOf(false)
        try {
            compose.setContent { NoveliaTheme("light") {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val controller = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                if (!reader) BookScreen(controller, ref, startAtContents = true)
                else {
                    val state = remember { controller.readerToc(ref) }
                    LaunchedEffect(state) { state.ensureLoaded("c", 1) }
                    ReaderTocPane(controller, ref, state, onRetry = {}, current = "c", scroll = rememberLazyListState(),
                        query = "", onQuery = {}, reversed = false, onReversed = {}, locateRequest = 0, onLocated = {}, onRead = {},
                        sourceParagraph = 1, anchors = anchors)
                }
            } }
            val mark = hasText("画页") and hasText("含插图")
            compose.waitUntil(10_000) { compose.onAllNodes(mark).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("前言") and hasClickAction()).assertIsDisplayed()
            compose.onAllNodesWithText("含插图").assertCountEquals(1)
            compose.runOnIdle { reader = true }
            compose.waitUntil(10_000) { compose.onAllNodes(mark and hasText("正在阅读")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(mark).assertIsSelected()
            compose.onAllNodesWithText("含插图").assertCountEquals(1)
        } finally {
            app.store.removeDocument(ref.id)
        }
    }

    @Test fun illustratedChapterIsMarkedInBothDirectories() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val ref = BookRef("syosetu", "toc-illustration-test")
        val toc = listOf(TocItem(titleJp = "第一章", chapterId = "1"), TocItem(titleJp = "第二章", chapterId = "2"))
        val readerToc = ReaderTocState { ReaderTocSnapshot(toc) }
        runBlocking { readerToc.ensureLoaded("1", toc.size) }
        try {
            app.store.cacheChapter(ref, "1", Chapter(paragraphs = listOf("<图片>https://example.org/1.png")))
            app.store.cacheChapter(ref, "2", Chapter(paragraphs = listOf("纯文字")))
            compose.setContent { NoveliaTheme("light") {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val controller = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                var current by remember { mutableStateOf<String?>(null) }
                val id = current
                if (id == null) TocPanel(controller, ref, toc, null) { current = it }
                else ReaderTocPane(controller, ref, readerToc, onRetry = {}, current = id, scroll = rememberLazyListState(),
                    query = "", onQuery = {}, reversed = false, onReversed = {}, locateRequest = 0, onLocated = {}, onRead = {})
            } }
            val marked = hasText("第一章") and hasText("含插图")
            compose.waitUntil(5_000) { compose.onAllNodes(marked).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("第二章") and hasText("可离线阅读")).assertIsDisplayed()
            compose.onAllNodesWithText("含插图").assertCountEquals(1)
            compose.onNode(marked).performClick()
            val activeMark = hasTestTag("reader-toc-chapter-1") and marked
            compose.waitUntil(5_000) { compose.onAllNodes(activeMark).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(activeMark).assertIsSelected()
            compose.onAllNodesWithText("含插图").assertCountEquals(1)
        } finally {
            app.store.clearChapterCache(ref)
        }
    }

    @Test fun chapterRowsFollowWallpaperOpacityAndSwitchBackToSolidColors() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val image = File.createTempFile("toc-wallpaper", ".png", app.cacheDir)
        val wallpaper = Color.White
        val base = ThemePreset("toc", "目录测试", accent = 0xBE4499, darkBackground = 0x232032,
            lightBackground = 0xF3E6D9, image = "fade".repeat(16), overlay = 0, cardOpacity = 50)
        var preset by mutableStateOf(base)
        var mode by mutableStateOf("dark")
        var rowColor = Color.Unspecified
        var currentColor = Color.Unspecified
        val toc = listOf(TocItem(titleJp = "第一章", chapterId = "1"), TocItem(titleJp = "第二章", chapterId = "2"))
        fun assertRowFill(label: String, expected: Color) {
            val pixels = compose.onNode(hasText(label) and hasClickAction()).captureToImage().toPixelMap()
            val actual = pixels[pixels.width * 3 / 4, pixels.height / 2]
            assertTrue("$label 行底色应为 $expected，实际为 $actual",
                abs(actual.red - expected.red) < .012f && abs(actual.green - expected.green) < .012f &&
                    abs(actual.blue - expected.blue) < .012f)
        }
        try {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            compose.setContent {
                NoveliaTheme(mode, preset = preset, imageFile = image) {
                    val row = ListItemDefaults.containerColor
                    val current = MaterialTheme.colorScheme.primaryContainer
                    SideEffect { rowColor = row; currentColor = current }
                    val c = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                    Column(Modifier.fillMaxSize().themePaper(MaterialTheme.colorScheme.background, LocalThemeWallpaper.current)) {
                        Spacer(Modifier.size(4.dp).testTag("toc-wallpaper-sample"))
                        TocPanel(c, BookRef("local", "toc-colors"), toc, "2") {}
                    }
                }
            }
            // 等待图片解码，再采样行内没有文字和图标的位置。
            compose.waitUntil(10_000) {
                compose.onNodeWithTag("toc-wallpaper-sample").captureToImage().toPixelMap()[1, 1] == wallpaper
            }
            assertRowFill("第一章", rowColor.copy(alpha = .5f).compositeOver(wallpaper))
            assertRowFill("第二章", currentColor.copy(alpha = .5f).compositeOver(wallpaper))
            compose.runOnIdle { preset = base.copy(cardOpacity = 100, accent = 0x2A81AC) }
            compose.waitForIdle()
            assertRowFill("第一章", rowColor)
            assertRowFill("第二章", currentColor)
            compose.runOnIdle { preset = base.copy(image = null); mode = "light" }
            compose.waitForIdle()
            assertRowFill("第一章", Color(0xFFF3E6D9))
            assertRowFill("第二章", currentColor)
        } finally { image.delete() }
    }

    @Test fun searchingOutTheCurrentChapterStillResumesItAndTheLastRowClearsTheButton() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val toc = List(50) { TocItem(titleJp = "目录章节 $it", chapterId = "$it") }
        var opened: String? = null
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
            Box(Modifier.requiredWidth(360.dp).height(580.dp)) {
                TocPanel(c, BookRef("local", "toc-continue-test"), toc, "7") { opened = it }
            }
        } } }
        compose.onNodeWithContentDescription("继续阅读").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("7", opened); opened = null }
        compose.onNodeWithText("搜索章节").performTextInput("不存在的章节")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("没有匹配的章节").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("继续阅读").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("7", opened) }
        compose.onNodeWithText("搜索章节").performTextClearance()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("目录章节 0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(49)
        val last = compose.onNodeWithText("目录章节 49").assertIsDisplayed().getUnclippedBoundsInRoot()
        val button = compose.onNodeWithTag("toc-continue-reading").getUnclippedBoundsInRoot()
        assertTrue(last.bottom <= button.top)
    }

    @Test fun anExplicitResumeActionUsesTheSameProgressChoiceAsTheSummary() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        var choseProgress = false
        var openedDirectly = false
        compose.setContent { NoveliaTheme("light") { AppInteractionMode(false, true) {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
            TocPanel(c, BookRef("local", "toc-continue-test"), listOf(TocItem(titleJp = "当前章", chapterId = "current")),
                "current", onContinue = { choseProgress = true }) { openedDirectly = true }
        } } }
        compose.onNodeWithContentDescription("继续阅读").performClick()
        compose.runOnIdle { assertTrue(choseProgress); assertFalse(openedDirectly) }
    }
}
