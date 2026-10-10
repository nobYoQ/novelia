package cc.novelia.app.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.ui.saveTestScreenshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class SquareCornersTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun visibleAppearanceToggleUpdatesWholeAppPersistsAndCanBeReversed() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val original = app.store.state.value
        try {
            compose.runOnIdle { app.store.update { it.copy(theme = "light", squareCorners = false, reducedMotion = true,
                reader = it.reader.copy(eInkMode = false)) } }
            compose.onNodeWithText("我的").performClick()
            assertHistoryCorner(square = false)
            openAppearance()
            option("fuck 圆角").assertIsOff()
            saveTestScreenshot("corners-settings-rounded.png")
            compose.onNodeWithText("fuck 圆角").performClick().assertIsOn()
            compose.runOnIdle { assertTrue(app.store.state.value.squareCorners) }
            runBlocking { app.store.flush() }
            assertTrue(LocalStore(app).state.value.squareCorners)
            compose.waitForIdle()
            saveTestScreenshot("corners-settings-square.png")
            returnToProfile()
            assertHistoryCorner(square = true)
            saveTestScreenshot("corners-profile-square.png")

            // 切换底部导航后，选中语义、点击和返回“我的”仍然可用。
            compose.onNodeWithText("书架").performClick()
            compose.onNodeWithText("我的").performClick()
            assertHistoryCorner(square = true)
            compose.runOnIdle { app.store.update { it.copy(theme = "dark") } }
            compose.waitForIdle()
            saveTestScreenshot("corners-profile-square-dark.png")
            compose.runOnIdle { app.store.update { it.copy(theme = "light") } }
            openAppearance()
            option("fuck 圆角").assertIsOn().performClick().assertIsOff()
            returnToProfile()
            assertHistoryCorner(square = false)
            saveTestScreenshot("corners-profile-restored.png")
        } finally {
            compose.runOnIdle { app.store.update { it.copy(theme = original.theme, squareCorners = original.squareCorners,
                reducedMotion = original.reducedMotion, reader = it.reader.copy(eInkMode = original.reader.eInkMode)) } }
            runBlocking { app.store.flush() }
        }
    }

    private fun openAppearance() {
        compose.onNodeWithTag("profile-list").performScrollToNode(hasTestTag("profile-settings"))
        compose.onNodeWithTag("profile-settings").performClick()
        option("外观与操作").performClick()
    }

    private fun returnToProfile() {
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("profile-list").performScrollToIndex(0)
        compose.waitForIdle()
    }

    private fun assertHistoryCorner(square: Boolean) {
        compose.onNodeWithTag("profile-list").performScrollToIndex(0)
        val pixels = compose.onNodeWithTag("profile-history").captureToImage().toPixelMap()
        val pixel = pixels[2, 2]
        val primary = Color(0xFF3E6B3B)
        val filled = abs(pixel.red - primary.red) < .02f && abs(pixel.green - primary.green) < .02f && abs(pixel.blue - primary.blue) < .02f
        assertEquals("阅读历史卡片的左上角应随开关变化", square, filled)
    }

    private fun option(title: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        return compose.onNodeWithText(title)
    }
}
