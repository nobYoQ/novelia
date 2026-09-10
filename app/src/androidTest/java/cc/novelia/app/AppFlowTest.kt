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
        val app = compose.activity.application as NoveliaApplication
        compose.runOnIdle {
            app.store.update { it.copy(positions = it.positions - "local/test-book", reader = ReaderSettings(), bookSettings = it.bookSettings - "local/test-book", theme = "light") }
            app.store.saveDocument(LocalDocument("test-book", "风与书页", "txt", listOf(LocalChapter("first", "第一章 出发", listOf("清晨的风轻轻翻过书页。旅人收拾行装，沿着森林小路向前走去。", "在那座遥远的小镇，每个人都有自己的故事。")), LocalChapter("second", "第二章 重逢", listOf("暮色降临，灯光从窗边洒落。")))))
            app.store.saveBook(BookCard(BookRef("local", "test-book"), "风与书页", subtitle = "TXT · 2 章"))
        }
        compose.onNodeWithText("本地文件").performClick()
        compose.onNodeWithText("风与书页").assertIsDisplayed()
        screenshot("shelf")
        compose.onNodeWithText("风与书页").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("第一章 出发").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("清晨的风轻轻翻过书页。旅人收拾行装，沿着森林小路向前走去。", substring = true).assertIsDisplayed()
        screenshot("reader")
        compose.onNodeWithContentDescription("阅读设置").performClick()
        compose.onNodeWithText("阅读偏好").assertIsDisplayed()
        screenshot("reader-settings")
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("下一章").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("第二章 重逢").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("暮色降临，灯光从窗边洒落。", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("阅读与外观").performClick()
        compose.onNodeWithText("深色", useUnmergedTree = true).performClick()
        screenshot("dark-settings")
    }
}
