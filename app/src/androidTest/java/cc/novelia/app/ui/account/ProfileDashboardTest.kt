package cc.novelia.app.ui.account

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileDashboardTest {
    @get:Rule val compose = createComposeRule()
    private val destinations = listOf("history", "downloads", "updates", "notes", "tools", "settings", "blocked", "sync", "backup", "about")

    @Test fun guestKeepsEveryDestinationAndIndependentCompanion() {
        val visits = mutableListOf<String>()
        var theme by mutableStateOf("light")
        var updateCount by mutableIntStateOf(2)
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = true) {
                NoveliaTheme(theme) {
                    Surface { ProfileDashboard(null, 7, updateCount, 0, visits::add, {}) }
                }
            }
        }
        compose.onNodeWithContentDescription("小绿，阅读搭子").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("小绿，阅读搭子")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "开心地笑了"))
        compose.runOnIdle { assertTrue(visits.isEmpty()) }
        compose.onNodeWithText("登录 / 注册").assertHeightIsAtLeast(48.dp).performClick()
        capture("profile-light")
        for(route in destinations) destination(route).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf("login") + destinations, visits) }
        compose.onNodeWithTag("profile-list").performScrollToIndex(0)
        compose.runOnIdle { theme = "dark" }
        capture("profile-dark")
        destination("notes")
        compose.onNodeWithText("7 条").assertIsDisplayed()
        destination("updates")
        compose.onNodeWithText("2 本更新").assertIsDisplayed()
        compose.runOnIdle { updateCount = 0 }
        compose.onNodeWithText("0 本更新").assertIsDisplayed()
    }

    @Test fun permissionsOverlayKeepsLayoutAndDismissesWithoutAccountActions() {
        var profile by mutableStateOf(Profile("阅读者", "member", System.currentTimeMillis() / 1000, Long.MAX_VALUE))
        var logouts = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppInteractionMode(eInk = false, reducedMotion = true) {
                NoveliaTheme("light") {
                    Surface {
                        Screen("我的") { padding ->
                            ProfileDashboard(profile, 12, 5, 3, {}, { logouts++ }, Modifier.padding(padding))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("普通成员").assertIsDisplayed()
        val roleBounds = compose.onNodeWithText("普通成员").getUnclippedBoundsInRoot()
        val cardBounds = compose.onNodeWithTag("profile-account-card").getUnclippedBoundsInRoot()
        assertTrue("账号文字应靠近卡片顶部", roleBounds.top - cardBounds.top <= 32.dp)
        compose.onNodeWithText("5 本更新").assertIsDisplayed()
        compose.onNodeWithText("社区发布").assertDoesNotExist()
        val accountBounds = compose.onNodeWithTag("profile-account-card").fetchSemanticsNode().boundsInRoot
        val readingBounds = compose.onNodeWithTag("profile-history").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("account-permissions-toggle").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("account-permissions-panel").assert(hasAnyAncestor(isPopup()))
        compose.onNodeWithText("可用").assertIsDisplayed()
        compose.onNodeWithText("受限").assertIsDisplayed()
        assertEquals(accountBounds, compose.onNodeWithTag("profile-account-card").fetchSemanticsNode().boundsInRoot)
        assertEquals(readingBounds, compose.onNodeWithTag("profile-history").fetchSemanticsNode().boundsInRoot)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("社区发布").assertIsDisplayed()
        assertEquals(readingBounds, compose.onNodeWithTag("profile-history").fetchSemanticsNode().boundsInRoot)
        capture("profile-account-expanded")
        compose.runOnIdle { profile = Profile("受限用户", "restricted", 0, Long.MAX_VALUE) }
        compose.onNodeWithText("受限账号").assertIsDisplayed()
        compose.onNodeWithText("社区发布").assertDoesNotExist()
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        compose.onAllNodesWithText("受限").assertCountEquals(2)
        compose.onNodeWithContentDescription("收起账号权限").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("account-permissions-panel").assertDoesNotExist()
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        compose.onNodeWithTag("account-permissions-panel").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("account-permissions-panel").assertDoesNotExist()
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        compose.onNodeWithTag("account-permissions-panel").assertIsDisplayed()
        tapAccountOutsidePopup()
        compose.onNodeWithTag("account-permissions-panel").assertDoesNotExist()
        compose.onNodeWithTag("account-permissions-toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"))
        compose.onNodeWithTag("profile-account-menu").performClick()
        compose.runOnIdle { assertEquals(0, logouts) }
        compose.onNodeWithTag("profile-logout").performClick()
        compose.runOnIdle { assertEquals(1, logouts) }
        destination("sync")
        compose.onNodeWithText("3 项待处理").assertIsDisplayed()
    }

    @Test fun narrowLargeTextAndEInkKeepAllActionsReachable() {
        val visits = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                AppInteractionMode(eInk = true, reducedMotion = true) {
                    NoveliaTheme("light") {
                        Surface(Modifier.requiredWidth(320.dp)) { ProfileDashboard(null, 128, 6, 4, visits::add, {}) }
                    }
                }
            }
        }
        compose.onNodeWithText("登录 / 注册").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("profile-wide-layout").assertDoesNotExist()
        for(route in destinations) {
            destination(route, eInk = true).assertHeightIsAtLeast(48.dp)
            assertTextFits(route)
            compose.onNodeWithTag("profile-$route").performClick()
        }
        compose.runOnIdle { assertEquals(listOf("login") + destinations, visits) }
        capture("profile-large-text-eink")
    }

    @Test fun wideLayoutAdaptsToFontSizeWithoutLosingAboutOrPermissions() {
        var fontScale by mutableFloatStateOf(1f)
        val visits = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AppInteractionMode(eInk = false, reducedMotion = true) {
                    NoveliaTheme("dark") {
                        Surface(Modifier.requiredWidth(960.dp).height(720.dp)) {
                            ProfileDashboard(Profile("阅读者", "admin", 0, Long.MAX_VALUE), 7, 4, 3, visits::add, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("profile-wide-layout").assertExists()
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        capture("profile-wide")
        compose.onNodeWithContentDescription("收起账号权限").performClick()
        destination("about").performClick()
        compose.onNodeWithTag("profile-list").performScrollToIndex(0)
        compose.runOnIdle { fontScale = 2f }
        compose.onNodeWithTag("profile-wide-layout").assertDoesNotExist()
        val readingBounds = compose.onNodeWithTag("profile-history").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        compose.onNodeWithText("社区发布").assertIsDisplayed()
        compose.onNodeWithText("书籍编辑").assertIsDisplayed()
        assertEquals(readingBounds, compose.onNodeWithTag("profile-history").fetchSemanticsNode().boundsInRoot)
        capture("profile-permissions-large-dark")
        compose.onNodeWithContentDescription("收起账号权限").performClick()
        destination("about").performClick()
        compose.runOnIdle { assertEquals(listOf("about", "about"), visits) }
    }

    private fun destination(route: String, eInk: Boolean = false): SemanticsNodeInteraction {
        val target = hasTestTag("profile-$route")
        if(eInk) {
            // 电子纸刻意没有 ScrollToIndex；用用户实际使用的翻屏入口推进列表。
            for(page in 0..20) {
                if(compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() && compose.onNode(target).isDisplayed()) {
                    return compose.onNode(target).assertIsDisplayed()
                }
                compose.onNodeWithText("下一屏").assertIsEnabled().performClick()
                compose.waitForIdle()
            }
            error("翻屏后仍未找到 $route")
        }
        compose.onNodeWithTag("profile-list").performScrollToNode(target)
        return compose.onNode(target).performScrollTo().assertIsDisplayed()
    }

    private fun assertTextFits(route: String) {
        val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag("profile-$route")) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        assertTrue(nodes.fetchSemanticsNodes().isNotEmpty())
        for(index in nodes.fetchSemanticsNodes().indices) {
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.forEach { layout ->
                for(line in 0 until layout.lineCount) {
                    assertTrue("$route text fits horizontally", layout.getLineLeft(line) >= -1f &&
                        layout.getLineRight(line) <= layout.size.width + 1f)
                    assertTrue("$route text fits vertically", layout.getLineBottom(line) <= layout.size.height + 1f)
                    assertFalse("$route text is not ellipsized", layout.isLineEllipsized(line))
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("profile-screenshots"), "$name.png")
        val bitmap = if(compose.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty()) instrumentation.uiAutomation.takeScreenshot()
            else compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tapAccountOutsidePopup() {
        val point = compose.onNodeWithTag("profile-account-card").fetchSemanticsNode().boundsInWindow.center
        val time = SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
        compose.waitForIdle()
    }
}
