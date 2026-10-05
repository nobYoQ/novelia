package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ForumPublishingNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noticeLinksKeepTheirForumCategorySearchAndRulesDestinations() {
        val opened = mutableListOf<String>()
        show(large = false, onOpen = opened::add)
        val node = compose.onNodeWithText("提问前先阅读", substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        for (label in listOf("站务公告", "意见反馈", "求书集中帖", "社区守则")) {
            val point = layouts.single().getBoundingBox(text.indexOf(label)).center
            node.performTouchInput { click(point) }
        }
        compose.runOnIdle { assertEquals(listOf(
            "https://forum.novelia.cc/c/announcements", "https://forum.novelia.cc/c/feedback",
            "https://forum.novelia.cc/c/novel?q=%E6%B1%82%E4%B9%A6%E9%9B%86%E4%B8%AD%E8%B4%B4", "https://forum.novelia.cc/rules"
        ), opened) }
        screenshot("publishing-notice-light")
    }

    @Test fun largeFontNoticeCanScrollThroughAllGuidance() {
        show(large = true) {}
        compose.onNodeWithText("发帖前请确认").assertIsDisplayed()
        screenshot("publishing-notice-dark-large-top")
        compose.onNodeWithTag("notice-scroll").performTouchInput { swipeUp() }
        screenshot("publishing-notice-dark-large-bottom")
    }

    private fun show(large: Boolean, onOpen: (String) -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if(large) 2f else 1f)) {
                NoveliaTheme(if(large) "dark" else "light") {
                    Column(Modifier.fillMaxSize().testTag("notice-scroll").verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ForumPublishingNotice(onOpen)
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "forum-update-20261003").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
