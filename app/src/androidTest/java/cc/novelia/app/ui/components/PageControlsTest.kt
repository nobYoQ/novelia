package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import cc.novelia.app.ui.components.base.PageControls
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class PageControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun numberedPagesAndValidatedJumpUseZeroBasedCallbacks() {
        var page by mutableIntStateOf(0)
        compose.setContent { NoveliaTheme("light") {
            Box(Modifier.safeDrawingPadding().width(360.dp)) { PageControls(page, 35) { page = it } }
        } }
        compose.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
        saveTestScreenshot("page-jump-collapsed.png")
        compose.onNodeWithContentDescription("第 35 页").performClick()
        compose.runOnIdle { assertEquals(34, page) }
        compose.onNodeWithContentDescription("下一页").assertIsNotEnabled()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").assertIsDisplayed()
        saveTestScreenshot("page-jump-expanded.png")
        compose.onNodeWithText("跳转页码").performTextInput("0")
        compose.onNodeWithText("跳转", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").performTextReplacement("36")
        compose.onNodeWithText("跳转", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("跳转页码").performTextReplacement("18")
        compose.onNodeWithText("跳转", substring = false).performClick()
        compose.runOnIdle { assertEquals(17, page) }
        compose.onNodeWithContentDescription("第 18 页").assertIsSelected()
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").performTextReplacement("1")
        compose.onNodeWithText("跳转页码").performImeAction()
        compose.runOnIdle { assertEquals(0, page) }
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
    }

    @Test fun jumpInputAnimatesClosedAndCanBeReopenedDuringTransition() {
        compose.setContent { NoveliaTheme("light") { PageControls(0, 20) {} } }
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").performTextInput("12")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("page-jump-input").assertExists()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("12").assertIsDisplayed()
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("跳转页码").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun navigationStaysCenteredWithOpenInputNarrowWidthsLargeTextAndRtl() {
        var width by mutableStateOf(360.dp)
        var fontScale by mutableFloatStateOf(1f)
        var count by mutableIntStateOf(35)
        var rtl by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if(rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                NoveliaTheme("light") { Surface {
                    Box(Modifier.safeDrawingPadding().width(width)) { PageControls(0, count) {} }
                } }
            }
        }
        fun assertCentered(inline: Boolean) {
            val controls = compose.onNodeWithTag("page-controls").fetchSemanticsNode().boundsInRoot
            val navigation = compose.onNodeWithTag("page-navigation").fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText("第 1 / $count 页").fetchSemanticsNode().boundsInRoot
            val toggle = compose.onNodeWithTag("page-jump-toggle").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertEquals("翻页组应相对整个分页区域居中", controls.center.x, navigation.center.x, 1f)
            assertEquals("页码文字应保持居中", controls.center.x, label.center.x, 1f)
            if(inline) {
                assertEquals(navigation.center.y, toggle.center.y, 1f)
                assertTrue("跳转入口不能覆盖翻页按钮", if(rtl) toggle.right <= navigation.left else toggle.left >= navigation.right)
            } else {
                assertTrue("空间不足时入口移至下一行", toggle.top >= navigation.bottom)
                assertEquals(controls.center.x, toggle.center.x, 1f)
            }
            compose.onNodeWithContentDescription("上一页").assertIsDisplayed()
            compose.onNodeWithContentDescription("下一页").assertIsDisplayed()
        }
        assertCentered(inline = true)
        saveControls("page-controls-centered.png")
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").assertIsDisplayed()
        assertCentered(inline = true)
        saveControls("page-controls-centered-expanded.png")
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.runOnIdle { width = 320.dp; fontScale = 2f; count = 9999 }
        assertCentered(inline = false)
        saveControls("page-controls-centered-large-text.png")
        compose.onNodeWithTag("page-jump-toggle").performClick()
        compose.onNodeWithText("跳转页码").assertIsDisplayed()
        assertCentered(inline = false)
        compose.runOnIdle { width = 360.dp; fontScale = 1f; count = 35; rtl = true }
        assertCentered(inline = true)
    }

    private fun saveControls(name: String) {
        val bitmap = compose.onNodeWithTag("page-controls").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
