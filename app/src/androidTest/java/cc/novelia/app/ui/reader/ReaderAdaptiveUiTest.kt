package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.ReaderScreen
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderAdaptiveUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directoryAndTextKeepTheirStateAcrossWideAndCompactLayouts() = withReader(1024.dp) { app, ref, resize ->
        compose.onNodeWithTag("reader-wide-layout").assertExists()
        compose.onNodeWithTag("reader-toc-chapter-first").assertIsSelected()
        compose.onNodeWithTag("reader-toc-query").performTextReplacement("第二")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader-toc-chapter-first").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 950f) }
        compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 0 }
        val before = app.store.state.value.positions.getValue(ref.key)
        compose.runOnIdle { resize(390.dp) }
        compose.onNodeWithTag("reader-compact-layout").assertExists()
        compose.onNodeWithTag("reader-toc-pane").assertDoesNotExist()
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.let { it.index == before.index && it.textOffset == before.textOffset } == true }
        compose.onNodeWithText("目录", substring = true).performClick()
        compose.onNodeWithTag("reader-toc-query").assertTextContains("第二")
        compose.onNodeWithText("关闭面板").performClick()
        compose.runOnIdle { resize(1024.dp) }
        compose.onNodeWithTag("reader-toc-query").assertTextContains("第二")
        compose.onNodeWithTag("reader-toc-chapter-second").performClick()
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
        compose.onNodeWithTag("reader-toc-chapter-second").assertIsSelected()
        compose.onNodeWithTag("reader-toc-query").assertTextContains("第二")
        compose.onNodeWithText("定位当前").performClick()
        compose.onNodeWithTag("reader-toc-chapter-second").assertIsDisplayed()
    }

    @Test fun failedEndPullPreservesTheChapterAndRetriesAfterTheFileRecovers() = verifyFailedPull(eInk = false)

    @Test fun eInkFailedEndPullCanBeDismissedAndRetriedWithoutLosingText() = verifyFailedPull(eInk = true)

    private fun verifyFailedPull(eInk: Boolean) = withReader(390.dp, eInk) { app, ref, _ ->
        val index = app.store.documentIndex(ref.id)
        val file = File(app.store.documentsDir, "${ref.id}-chapters/${index.chapterFiles.getValue("second")}.json")
        val original = file.readBytes()
        try {
            file.writeText("interrupted test fixture", Charsets.UTF_8)
            compose.onNodeWithTag("reader-scroll").performTouchInput { click(center) }
            compose.onNodeWithTag("reader-top-toolbar").assertDoesNotExist()
            compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
            val before = scrollPosition()
            val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            compose.onNodeWithTag("reader-scroll").performTouchInput {
                down(center); moveBy(Offset(0f, -100f * density), delayMillis = 250); up()
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("章节加载失败，仍在当前章").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("本章完").assertIsDisplayed()
            assertEquals(before, scrollPosition(), .001f)
            assertEquals("first", app.store.state.value.positions[ref.key]?.chapterId)
            if(eInk) {
                compose.onNodeWithText("继续阅读").performClick()
                compose.onNodeWithTag("reader-chapter-load-status").assertDoesNotExist()
                assertEquals(before, scrollPosition(), .001f)
                compose.onNodeWithText("阅读下一章").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithText("章节加载失败，仍在当前章").fetchSemanticsNodes().isNotEmpty() }
            }
            file.writeBytes(original)
            compose.onNodeWithText("重试加载章节").performClick()
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
            compose.onNodeWithText("恢复后的下一章正文。", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("reader-chapter-load-status").assertDoesNotExist()
            compose.onNodeWithTag("reader-top-toolbar").assertDoesNotExist()
        } finally { file.writeBytes(original) }
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("reader-scroll").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun withReader(initialWidth: Dp, eInk: Boolean = false, block: (NoveliaApplication, BookRef, (Dp) -> Unit) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val previous = app.store.state.value
        val ref = BookRef("local", "adaptive-reader-regression")
        var width by mutableStateOf(initialWidth)
        lateinit var controller: AppController
        try {
            app.store.saveDocument(LocalDocument(ref.id, "阅读布局测试", "txt", listOf(
                LocalChapter("first", "第一章 林间", List(12) { paragraph -> "第${paragraph + 1}段，沿着森林小路走向远处的小镇。".repeat(80) }),
                LocalChapter("second", "第二章 归途", listOf("恢复后的下一章正文。"))
            )))
            app.store.update { it.copy(reader = ReaderSettings(paginationMode = "scroll", width = 900f, eInkMode = eInk, showPageButtons = false, prefetchChapters = 0),
                reducedMotion = true, historyPaused = false, positions = it.positions - ref.key, bookSettings = it.bookSettings - ref.key) }
            compose.setContent {
                NoveliaTheme("light") {
                    AppInteractionMode(eInk, true) {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val snackbar = remember { SnackbarHostState() }
                        controller = remember(nav, scope, snackbar) { AppController(app, nav, scope, snackbar) }
                        Box(Modifier.fillMaxSize().wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(width)) {
                            NavHost(nav, startDestination = "home") {
                                composable("home") { Text("测试书架") }
                                composable("reader/{provider}/{book}/{chapter}") { entry ->
                                    ReaderScreen(controller, BookRef(entry.arguments?.getString("provider").orEmpty(), entry.arguments?.getString("book").orEmpty()), entry.arguments?.getString("chapter").orEmpty())
                                }
                            }
                        }
                    }
                }
            }
            compose.runOnIdle { controller.read(ref, "first") }
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterId == "first" }
            compose.waitForIdle()
            block(app, ref) { width = it }
        } finally {
            compose.runOnIdle { controller.back() }
            app.store.update { previous }
        }
    }
}
