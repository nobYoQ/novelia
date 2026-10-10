package cc.novelia.app.ui.account

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.AppIconButton
import cc.novelia.app.ui.navigation.RootDestinationLayout
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProfileLandscapeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun landscapeUsesBothColumnsFromTheTopAndKeepsEveryActionInShortWindows() {
        val member = Profile("阅读者", "member", 1743206400, Long.MAX_VALUE)
        var profile by mutableStateOf<Profile?>(member)
        var square by mutableStateOf(false)
        var theme by mutableStateOf("dark")
        var height by mutableStateOf(680.dp)
        var width by mutableStateOf(1040.dp)
        var fontScale by mutableFloatStateOf(1f)
        val visits = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AppInteractionMode(eInk = false, reducedMotion = true) {
                    NoveliaTheme(theme, squareCorners = square) {
                        // Fixture dimensions describe app content, independent of the host emulator's system bars.
                        Surface(Modifier.requiredSize(width, height).consumeWindowInsets(WindowInsets.safeDrawing)
                            .testTag("profile-landscape-preview")) {
                            RootDestinationLayout("profile", visits::add) {
                                Screen("我的", actions = {
                                    AppIconButton({ visits += "settings" }) { Icon(Icons.Outlined.Tune, "设置") }
                                }) { padding ->
                                    ProfileDashboard(profile, 7, 0, visits::add, {}, Modifier.padding(padding))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("profile-wide-layout").assertExists()
        val account = compose.onNodeWithTag("profile-account-card").getUnclippedBoundsInRoot()
        val preferences = compose.onNodeWithTag("profile-preferences").getUnclippedBoundsInRoot()
        val dashboard = compose.onNodeWithTag("profile-dashboard").getUnclippedBoundsInRoot()
        assertTrue("账号卡不再占据整行", account.right - account.left < (dashboard.right - dashboard.left) * .55f)
        assertEquals("两栏从同一高度开始", account.top.value, preferences.top.value, 1f)
        assertTrue("账号卡应紧凑", account.bottom - account.top <= 204.dp)
        assertAlignedBottoms()
        assertTrue("关于卡应完整显示", compose.onNodeWithTag("profile-about").getUnclippedBoundsInRoot().bottom <= dashboard.bottom)
        compose.onNodeWithTag("profile-about").assertIsDisplayed()
        capture("profile-landscape-dark")
        compose.onNodeWithContentDescription("小绿，阅读搭子").performClick()
        compose.onNodeWithTag("profile-account-menu").performClick()
        compose.onNodeWithTag("profile-logout").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        compose.onNodeWithTag("account-permissions-panel").assertIsDisplayed()
        assertEquals(account, compose.onNodeWithTag("profile-account-card").getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("收起账号权限").performClick()

        compose.runOnIdle { square = true }
        assertAlignedBottoms()
        capture("profile-landscape-square")
        compose.runOnIdle { square = false; theme = "light"; profile = null }
        compose.onNodeWithText("登录 / 注册").performClick()
        assertAlignedBottoms()
        capture("profile-landscape-guest")

        compose.runOnIdle { width = 940.dp }
        assertAlignedBottoms()
        compose.runOnIdle { width = 1040.dp; profile = member; fontScale = 1.1f }
        assertAlignedBottoms()

        compose.runOnIdle { fontScale = 1f; theme = "dark"; height = 360.dp }
        val routes = listOf("history", "downloads", "updates", "notes", "tools", "settings", "blocked", "sync", "backup", "about")
        for(route in routes) {
            val match = hasTestTag("profile-$route")
            compose.onNodeWithTag("profile-list").performScrollToNode(match)
            compose.onNode(match).performScrollTo().assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(listOf("login") + routes, visits) }
        compose.onNodeWithTag("profile-list").performScrollToIndex(0)
        capture("profile-landscape-short")
    }

    private fun assertAlignedBottoms() {
        compose.onNodeWithTag("profile-wide-layout").assertExists()
        val left = compose.onNodeWithTag("profile-shortcuts").getUnclippedBoundsInRoot()
        val right = compose.onNodeWithTag("profile-about").getUnclippedBoundsInRoot()
        assertEquals("两栏底边应对齐", left.bottom.value, right.bottom.value, .5f)
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("profile-landscape-preview").captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("profile-landscape")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
