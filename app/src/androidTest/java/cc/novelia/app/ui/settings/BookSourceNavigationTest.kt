package cc.novelia.app.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.network.BookSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookSourceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun changingSourceFromSettingsOpensMirrorLoginAndCanSwitchBack() {
        val app = compose.activity.application as NoveliaApplication
        val previous = app.bookSources.capture().source
        try {
            app.bookSources.select(BookSource.ORIGINAL)
            compose.waitUntil(15_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("我的").performClick()
            compose.onNodeWithText("设置", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("网络与同步").performScrollTo().performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("书源线路"))
            compose.onNodeWithText("书源线路").performClick()
            compose.waitForIdle()
            screenshot(app, "mirror-source-picker.png")
            compose.onNodeWithTag("book-source-xkvi").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("通过镜像登录").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("用户名或邮箱").assertIsDisplayed()
            compose.onNodeWithText("密码").assertIsDisplayed()
            screenshot(app, "mirror-login-screen.png")
            assertEquals(BookSource.XKVI, app.bookSources.capture().source)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("通过镜像登录").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("切换书源线路").performScrollTo().performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("书源线路"))
            compose.onNodeWithText("书源线路").performClick()
            compose.onNodeWithTag("book-source-original").performClick()
            compose.waitUntil(10_000) { app.bookSources.capture().source == BookSource.ORIGINAL }
            assertEquals(BookSource.ORIGINAL, app.bookSources.capture().source)
        } finally { app.bookSources.select(previous) }
    }
    private fun screenshot(app: NoveliaApplication, name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
