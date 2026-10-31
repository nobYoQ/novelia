package cc.novelia.app.ui.account

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.network.BookSource
import cc.novelia.app.data.network.SourceSelection
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 仅使用虚构输入与本地校验，不提交认证或邮件请求。 */
class LoginLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var width by mutableStateOf(412.dp)
    private var height by mutableStateOf(780.dp)
    private var fontScale by mutableFloatStateOf(1f)
    private var theme by mutableStateOf("light")
    private var reducedMotion by mutableStateOf(true)
    private var eInk by mutableStateOf(false)
    private var square by mutableStateOf(false)
    private var hideKeyboard: () -> Unit = {}

    private fun showLogin() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        compose.setContent {
            val keyboard = LocalSoftwareKeyboardController.current
            SideEffect { hideKeyboard = { keyboard?.hide() } }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                AppInteractionMode(eInk = eInk, reducedMotion = reducedMotion) {
                    NoveliaTheme(theme, squareCorners = square) {
                        Surface(Modifier.requiredSize(width, height).consumeWindowInsets(WindowInsets.safeDrawing)
                            .testTag("login-preview")) {
                            val nav = rememberNavController()
                            val scope = rememberCoroutineScope()
                            val snackbar = remember { SnackbarHostState() }
                            val controller = remember { AppController(app, nav, scope, snackbar) }
                            LoginForm(controller, SourceSelection(BookSource.ORIGINAL), forum = false)
                        }
                    }
                }
            }
        }
    }

    @Test fun interactiveStickerPasswordVisibilityAndAccountModesKeepWorking() {
        showLogin()
        compose.onNodeWithTag("login-submit").assertIsDisplayed().assertIsNotEnabled()
        val companion = compose.onNodeWithContentDescription("小绿，阅读搭子")
        companion.assertIsDisplayed().assertIsEnabled()
        val before = companion.getUnclippedBoundsInRoot()
        capture("login-light")
        compose.runOnIdle { reducedMotion = false }
        compose.mainClock.autoAdvance = false
        try {
            companion.performClick()
            compose.mainClock.advanceTimeBy(220)
            companion.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "开心地笑了"))
            assertEquals(before, companion.getUnclippedBoundsInRoot())
            capture("login-sticker-reaction")
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { reducedMotion = true }

        compose.onNodeWithTag("login-password").performTextInput("synthetic-password")
        compose.onNodeWithTag("login-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithContentDescription("显示密码").performClick()
        assertEquals("synthetic-password", displayedPassword())
        compose.onNodeWithContentDescription("隐藏密码").performClick()
        assertTrue("隐藏后不应绘制密码原文", displayedPassword() != "synthetic-password")
        dismissKeyboard()
        compose.onNodeWithText("没有账号，注册").performScrollTo().performClick()
        compose.onNodeWithTag("login-password").assertTextEquals("密码", "")
        compose.onNodeWithTag("login-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("login-otp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-otp").assertTextEquals("邮箱验证码（6 位数字）", "")
        compose.onNodeWithTag("login-send-otp").performScrollTo().assertIsNotEnabled()
        capture("login-register")
        compose.onNodeWithTag("login-username").performScrollTo().performTextInput("reader")
        compose.onNodeWithTag("login-password").performScrollTo().performTextInput("short")
        dismissKeyboard()
        compose.onNodeWithTag("login-submit").performScrollTo().performClick()
        compose.onNodeWithText("密码应为 8–100 个字符").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已有账号，去登录").performScrollTo().performClick()
        compose.onNodeWithText("忘记密码？").performScrollTo().performClick()
        compose.onNodeWithTag("login-username").assertDoesNotExist()
        compose.onNodeWithTag("login-password").assertTextEquals("新密码", "")
        compose.onNodeWithTag("login-otp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-otp").assertTextEquals("邮箱验证码（26 位字符）", "")
        compose.onNodeWithTag("login-send-otp").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("login-password").performScrollTo().performTextInput("synthetic-password")
        compose.onNodeWithTag("login-email").performScrollTo().performTextInput("reader@example.invalid")
        compose.onNodeWithTag("login-otp").performScrollTo().performTextInput("123456")
        dismissKeyboard()
        compose.onNodeWithTag("login-submit").performScrollTo().performClick()
        compose.onNodeWithText("找回密码验证码应为 26 位字符，请完整复制邮件中的验证码").performScrollTo().assertIsDisplayed()
        capture("login-reset")
    }

    @Test fun wideShortAndLargeTextLayoutsKeepAllControlsReachable() {
        width = 960.dp
        height = 540.dp
        theme = "dark"
        showLogin()
        compose.onNodeWithTag("login-wide-layout").assertExists()
        val welcome = compose.onNodeWithTag("login-welcome").getUnclippedBoundsInRoot()
        val field = compose.onNodeWithTag("login-username").getUnclippedBoundsInRoot()
        assertTrue("宽屏表单应与欢迎区并列", field.left > welcome.right)
        compose.onNodeWithTag("login-submit").assertIsDisplayed()
        capture("login-wide-dark")
        compose.runOnIdle { height = 320.dp }
        compose.onNodeWithText("没有账号，注册").performScrollTo().performClick()
        compose.onNodeWithTag("login-otp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已有账号，去登录").performScrollTo().performClick()

        compose.runOnIdle { width = 320.dp; height = 640.dp; fontScale = 1.8f }
        compose.onNodeWithTag("login-wide-layout").assertDoesNotExist()
        compose.onNodeWithTag("login-username").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-password").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("忘记密码？").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-source-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("login-source-original").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("login-source-xkvi").assertIsNotEnabled()
        capture("login-large-dark")
        compose.runOnIdle { eInk = true; square = true; theme = "light" }
        compose.onNodeWithTag("login-source-toggle").performScrollTo().performClick()
        compose.onNodeWithText("没有账号，注册").performScrollTo().performClick()
        compose.onNodeWithTag("login-otp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("login-submit").performScrollTo().assertIsDisplayed()
        capture("login-large-eink")
        compose.onNodeWithContentDescription("小绿，阅读搭子").assertIsNotEnabled()
    }

    private fun dismissKeyboard() {
        // No-op when the IME is already hidden; a back key would finish the test Activity.
        compose.runOnIdle { hideKeyboard() }
        compose.waitForIdle()
    }

    private fun displayedPassword(): String {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("login-password").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.text.text
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("login-preview").captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("login-screenshots")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
