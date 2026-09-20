package cc.novelia.app.ui.reader

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.ReaderSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EInkReaderFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bothPaginationModesUseTheButtonVisibilityPreference() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val ref = BookRef("local", "paging-modes-regression")
        try {
            compose.runOnIdle {
                app.store.update { it.copy(reader = ReaderSettings().withEInkMode(true).withPaginationMode("scroll"),
                    bookSettings = it.bookSettings - ref.key, positions = it.positions - ref.key, historyPaused = false) }
                app.store.saveDocument(LocalDocument(ref.id, "分页模式测试", "txt", listOf(
                    LocalChapter("first", "第一章", List(40) { "第 ${it + 1} 段。" + "旅人沿着森林小路前行。".repeat(8) }))))
                app.store.saveBook(BookCard(ref, "分页模式测试"))
            }
            compose.onNodeWithText("本地文件").performClick()
            compose.onNodeWithText("分页模式测试").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("下一屏").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-scroll").assertExists()
            compose.onNodeWithText("下一屏").performClick()
            compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.index ?: 0) > 0 }
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("连续滚动").performScrollTo().assertIsSelected()
            compose.onNodeWithText("显示翻页按钮").performScrollTo().performClick()
            compose.onNodeWithText("关闭面板").performClick()
            compose.onNodeWithText("下一屏").assertDoesNotExist()
            compose.onNodeWithText("上一屏").assertDoesNotExist()
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("自动分页").performScrollTo().performClick()
            screenshot("pagination-preferences")
            compose.onNodeWithText("关闭面板").performClick()
            compose.onNodeWithTag("reader-page").assertExists()
            compose.onNodeWithTag("reader-scroll").assertDoesNotExist()
            compose.onNodeWithText("下一页").assertDoesNotExist()
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("显示翻页按钮").performScrollTo().performClick()
            compose.onNodeWithText("关闭面板").performClick()
            compose.onNodeWithText("下一页").assertIsDisplayed()
            compose.waitForIdle()
            val anchor = app.store.state.value.positions.getValue(ref.key).let { it.index to it.textOffset }
            compose.onNodeWithText("下一页").performClick()
            compose.waitUntil(10_000) { app.store.state.value.positions.getValue(ref.key).let { it.index to it.textOffset } != anchor }
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("连续滚动").performScrollTo().performClick()
            compose.onNodeWithText("关闭面板").performClick()
            compose.onNodeWithText("下一屏").assertIsDisplayed()
            compose.onNodeWithText("下一页").assertDoesNotExist()
            compose.onNodeWithTag("reader-scroll").assertExists()
        } finally {
            compose.runOnIdle { app.store.update { previous } }
        }
    }

    @Test fun staticReaderSupportsPhysicalKeysSearchAndRestoringLongParagraphs() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val ref = BookRef("local", "eink-regression")
        try {
            compose.runOnIdle {
                app.store.update { it.copy(reader = ReaderSettings(eInkMode = true, volumeKeys = true, theme = "paper"), bookSettings = it.bookSettings - ref.key, positions = it.positions - ref.key, historyPaused = false) }
                app.store.saveDocument(LocalDocument(ref.id, "电子纸分页测试", "txt", listOf(
                    LocalChapter("first", "第一章 旅途", listOf("旅人沿着森林小路前行，寻找远处的小镇。".repeat(180), "独特的终点标记。")),
                    LocalChapter("second", "第二章 归来", listOf("星光照亮归途。")))))
                app.store.saveBook(BookCard(ref, "电子纸分页测试"))
            }
            compose.onNodeWithText("本地文件").performClick()
            compose.onNodeWithText("电子纸分页测试").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("下一页").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterId == "first" }
            compose.onNodeWithText("下一页").performClick()
            compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 0 }
            val firstOffset = app.store.state.value.positions.getValue(ref.key).textOffset
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            // Native input injection must wait for the preceding Compose touch/focus work.
            compose.waitForIdle()
            compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
            compose.waitForIdle()
            compose.waitUntil(10_000) { app.store.state.value.positions.getValue(ref.key).textOffset > firstOffset }
            val saved = app.store.state.value.positions.getValue(ref.key)
            // A multi-screen paragraph must keep its character anchor through both modes.
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("连续滚动").performScrollTo().performClick()
            compose.onNodeWithText("关闭面板").performClick()
            compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.offset ?: 0) > 0 }
            compose.runOnIdle {
                val current = app.store.state.value.positions.getValue(ref.key)
                assertEquals(saved.index, current.index)
                assertEquals(saved.textOffset, current.textOffset)
            }
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("自动分页").performScrollTo().performClick()
            compose.onNodeWithText("关闭面板").performClick()
            compose.waitUntil(10_000) { (app.store.state.value.positions[ref.key]?.textOffset ?: 0) > 0 && app.store.state.value.positions[ref.key]?.offset == 0 }
            compose.runOnIdle { assertEquals(saved.textOffset, app.store.state.value.positions.getValue(ref.key).textOffset) }
            screenshot("eink-reader")
            val restored = app.store.state.value.positions.getValue(ref.key)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("电子纸分页测试").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("搜索本章").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(restored.textOffset, app.store.state.value.positions.getValue(ref.key).textOffset) }
            compose.onNodeWithContentDescription("搜索本章").performClick()
            compose.onNodeWithText("搜索本章段落").performTextInput("独特的终点")
            compose.onNodeWithText("查找").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("独特的终点标记。", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("搜索本章").performClick()
            compose.onNodeWithContentDescription("阅读设置").performClick()
            compose.onNodeWithText("电子纸阅读模式").performScrollTo().assertIsDisplayed()
            screenshot("eink-settings")
            compose.onNodeWithText("关闭面板").performClick()
            compose.onNodeWithText("下一页").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("第二章 归来").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("上一页").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("独特的终点标记。", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("我的").performClick()
            repeat(5) {
                if(compose.onAllNodesWithText("阅读与外观").fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("下一屏").performClick()
            }
            compose.onNodeWithText("阅读与外观").performScrollTo().performClick()
            compose.onNodeWithText("滚动时自动收起筛选").performScrollTo().assertIsDisplayed()
            val oldCollapse = app.store.state.value.autoCollapseCloudFilters
            compose.onNodeWithText("滚动时自动收起筛选").performClick()
            compose.runOnIdle { assertEquals(!oldCollapse, app.store.state.value.autoCollapseCloudFilters) }
        } finally {
            compose.runOnIdle { app.store.update { previous } }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("screenshots"), "$name.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
