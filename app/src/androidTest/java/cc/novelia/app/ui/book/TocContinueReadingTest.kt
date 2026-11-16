package cc.novelia.app.ui.book

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
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
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.theme.LocalThemeWallpaper
import cc.novelia.app.ui.theme.themePaper
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TocContinueReadingTest {
    @get:Rule val compose = createComposeRule()

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
