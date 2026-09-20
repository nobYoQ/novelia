package cc.novelia.app.ui.account

import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileStickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("sticker-screenshots"), "$name.png")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun profileKeepsAccountActionsAndSupportsDarkAndReducedMotion() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        try {
            compose.runOnIdle { app.store.update { it.copy(theme = "light", reducedMotion = false) } }
            compose.onNodeWithText("我的").performClick()
            val companion = compose.onNodeWithContentDescription("小绿，阅读搭子")
            companion.assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("你的阅读搭子").assertDoesNotExist()
            compose.onNodeWithText("戳戳我", substring = true).assertDoesNotExist()
            compose.onNodeWithText("登录 / 注册").assertExists().assertHasClickAction()
            screenshot("profile-idle")
            compose.mainClock.autoAdvance = false
            companion.performClick()
            compose.mainClock.advanceTimeBy(220)
            companion.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "开心地笑了"))
            screenshot("profile-happy")
            companion.performClick()
            compose.mainClock.advanceTimeBy(80)
            companion.performClick()
            compose.mainClock.advanceTimeBy(240)
            screenshot("profile-love")
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { app.store.update { it.copy(theme = "dark", reducedMotion = true) } }
            companion.performClick()
            screenshot("profile-dark-reduced-motion")
            compose.onNodeWithText("登录 / 注册").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("登录 Novelia").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { app.store.update { it.copy(theme = previous.theme, reducedMotion = previous.reducedMotion) } }
        }
    }
}
