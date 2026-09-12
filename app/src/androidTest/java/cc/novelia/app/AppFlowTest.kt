package cc.novelia.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("screenshots"), "$name.png")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    @Test fun nativeReaderKeepsLocalBookAndPreferencesAcrossNavigation() {
        try {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        compose.runOnIdle {
            app.store.update { it.copy(positions = it.positions - "local/test-book", reader = ReaderSettings(), bookSettings = it.bookSettings - "local/test-book", theme = "light", reducedMotion = false) }
            app.store.saveDocument(LocalDocument("test-book", "风与书页", "txt", listOf(LocalChapter("first", "第一章 出发", listOf("清晨的风轻轻翻过书页。旅人收拾行装，沿着森林小路向前走去。", "在那座遥远的小镇，每个人都有自己的故事。")), LocalChapter("second", "第二章 重逢", listOf("暮色降临，灯光从窗边洒落。")))))
            app.store.saveBook(BookCard(BookRef("local", "test-book"), "风与书页", subtitle = "TXT · 2 章"))
        }
        compose.onNodeWithText("本地文件").performClick()
        compose.onNodeWithText("风与书页").assertIsDisplayed()
        screenshot("shelf")
        compose.onNodeWithContentDescription("批量整理").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("移动", substring = true).assertIsEnabled()
        screenshot("shelf-selection")
        compose.onNodeWithContentDescription("完成整理").performClick()
        compose.onNodeWithText("全选").assertDoesNotExist()
        compose.onNodeWithText("我的收藏").performClick()
        compose.onNodeWithText("本地文件").performClick()
        compose.onNodeWithText("风与书页").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("第一章 出发").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("清晨的风轻轻翻过书页。旅人收拾行装，沿着森林小路向前走去。", substring = true).assertIsDisplayed()
        screenshot("reader")
        compose.onNodeWithContentDescription("搜索本章").performClick()
        compose.onNodeWithText("搜索本章段落").performTextInput("遥远")
        compose.onNodeWithText("查找").performClick()
        compose.onNodeWithContentDescription("搜索本章").performClick()
        compose.onNodeWithText("搜索本章段落").assertDoesNotExist()
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("阅读偏好").assertIsDisplayed()
        screenshot("reader-settings")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("下一章").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("第二章 重逢").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("暮色降临，灯光从窗边洒落。", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的").performClick()
        val originalDownloads = app.store.state.value.downloads
        try {
            compose.runOnIdle {
                app.store.update { it.copy(downloads = listOf(DownloadEntry("motion-preview", "风与书页 · 离线副本", "motion-preview.txt", "https://example.invalid/preview", status = "下载中", progress = 12))) }
            }
            compose.onNodeWithText("下载管理").performClick()
            compose.runOnIdle { app.store.update { it.copy(downloads = it.downloads.map { entry -> entry.copy(progress = 67) }) } }
            compose.onNodeWithText("下载中 · 67%").assertIsDisplayed()
            screenshot("download-progress")
            compose.runOnIdle { app.store.update { it.copy(downloads = it.downloads.map { entry -> entry.copy(status = "已完成", progress = 100) }) } }
            compose.onNodeWithText("已完成").assertIsDisplayed()
            compose.onNodeWithText("导入阅读").assertIsDisplayed()
            screenshot("download-complete")
            compose.onNodeWithContentDescription("返回").performClick()
        } finally {
            compose.runOnIdle { app.store.update { it.copy(downloads = originalDownloads) } }
        }
        compose.onNodeWithText("阅读与外观").performClick()
        compose.onNodeWithText("深色", useUnmergedTree = true).performClick()
        compose.onNodeWithText("减少动态效果").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertTrue(app.store.state.value.reducedMotion)
            val bars = androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            org.junit.Assert.assertFalse(bars.isAppearanceLightStatusBars)
            org.junit.Assert.assertFalse(bars.isAppearanceLightNavigationBars)
        }
        screenshot("dark-settings")
        } catch (error: Throwable) {
            android.util.Log.e("NoveliaFlowTest", "Local reading regression failed before activity teardown", error)
            throw error
        }
    }
}
