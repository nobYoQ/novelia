package cc.novelia.app.ui.reader

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderCustomColors
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderThemeEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exactColorInputValidatesBeforeApplyingAndCanRestoreDefaults() {
        var reader by mutableStateOf(ReaderSettings(theme = "custom"))
        compose.setContent { NoveliaTheme("light") { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { ReaderPreferences(reader) { reader = it } } } }
        compose.onNodeWithTag("reader-color-text").performScrollTo().performClick()
        compose.onNodeWithText("输入色值").performClick()
        compose.onNodeWithTag("reader-color-input").performTextReplacement("#GGGGGG")
        compose.onNodeWithText("应用颜色").assertIsNotEnabled()
        compose.onNodeWithTag("reader-color-input").performTextReplacement("#123abc")
        compose.onNodeWithText("应用颜色").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0x123ABCL, reader.customColors.text) }
        compose.onNodeWithText("重置自定义配色").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReaderCustomColors(), reader.customColors) }
    }

    @Test fun eInkColorSwatchesHaveReachableControlsAndOnlyChangeChosenColor() {
        var reader by mutableStateOf(ReaderSettings(theme = "custom"))
        compose.setContent { AppInteractionMode(true, true) { NoveliaTheme("light") { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { ReaderPreferences(reader) { reader = it } } } } }
        fun choose(field: String, value: String) {
            compose.onNodeWithTag("reader-color-$field").performScrollTo().performClick()
            compose.onNodeWithContentDescription("常用颜色 $value").performScrollTo().performClick()
            compose.onNodeWithText("应用颜色").performClick()
        }
        choose("background", "#141A16")
        compose.runOnIdle {
            assertEquals(0x141A16L, reader.customColors.background)
            assertEquals(ReaderCustomColors().text, reader.customColors.text)
        }
        compose.onNodeWithTag("reader-color-background").assertTextContains("#141A16")
        choose("text", "#DDE5DC")
        choose("toolbar", "#141A16")
        compose.onNodeWithTag("reader-color-preview").performScrollTo()
        compose.onNodeWithText("这是一行句子").assertIsDisplayed()
        saveTestScreenshot("problem-reader-custom-colors.png")
    }

    @Test fun fullSpectrumPickerSupportsSlidersAndDraggingWithoutCommittingUntilApplied() {
        var reader by mutableStateOf(ReaderSettings(theme = "custom"))
        compose.setContent { NoveliaTheme("light") { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { ReaderPreferences(reader) { reader = it } } } }
        compose.onNodeWithTag("reader-color-background").performScrollTo().performClick()
        compose.onNodeWithTag("reader-color-hue").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(240f) }
        compose.onNodeWithTag("reader-color-saturation").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("reader-color-brightness").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("#0000FF").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ReaderCustomColors(), reader.customColors) }
        compose.onNodeWithTag("reader-color-plane").performScrollTo().performTouchInput {
            swipe(Offset(width * .25f, height * .25f), Offset(width * .75f, height * .5f), 250)
        }
        compose.onNodeWithText("应用颜色").performClick()
        compose.runOnIdle {
            val color = reader.customColors.background
            assertTrue((color shr 16 and 0xFF) in 30L..33L)
            assertTrue((color shr 8 and 0xFF) in 30L..33L)
            assertTrue((color and 0xFF) in 126L..129L)
            assertEquals(ReaderCustomColors().text, reader.customColors.text)
            assertEquals(ReaderCustomColors().toolbar, reader.customColors.toolbar)
        }
        val applied = reader.customColors
        compose.onNodeWithTag("reader-color-background").performClick()
        compose.onNodeWithText("输入色值").performClick()
        compose.onNodeWithTag("reader-color-input").performScrollTo().performTextReplacement("#FF00FF")
        compose.onNodeWithTag("reader-color-input-mode").performClick()
        compose.onNodeWithTag("reader-color-plane").performScrollTo()
        saveTestScreenshot("problem-reader-color-picker.png")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(applied, reader.customColors) }
    }

    @Test fun pickerPreservesDraftAcrossRecreationAndRemainsReachableWithLargeText() {
        var applied: Long? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppInteractionMode(true, true) { NoveliaTheme("light") {
                    ReaderColorPicker("背景颜色", 0xFFFFFFL, onDismiss = {}, onApply = { applied = it })
                } }
            }
        }
        // 白色没有色相，先选绿色再提高饱和度也应得到绿色。
        compose.onNodeWithTag("reader-color-hue").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(120f) }
        compose.onNodeWithTag("reader-color-saturation").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("reader-color-brightness").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        compose.onNodeWithTag("reader-color-input-mode").performScrollTo().performClick()
        compose.onNodeWithTag("reader-color-input").assertTextContains("#008000")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reader-color-input").assertTextContains("#008000")
        compose.onNodeWithTag("reader-color-input-mode").performClick()
        compose.onNodeWithContentDescription("常用颜色 #DDE5DC").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reader-color-plane").performScrollTo()
        saveTestScreenshot("problem-reader-color-picker-large-text.png")
        compose.onNodeWithText("应用颜色").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0x008000L, applied) }
    }

    @Test fun manualEntryOpensKeyboardAndAcceptsTypedColorsForEveryField() = verifyTypedColors(false)

    @Test fun eInkManualEntryOpensKeyboardAndAcceptsTypedColorsForEveryField() = verifyTypedColors(true)

    private fun verifyTypedColors(eInk: Boolean) {
        var reader by mutableStateOf(ReaderSettings(theme = "custom"))
        compose.setContent { AppInteractionMode(eInk, eInk) { NoveliaTheme("light") {
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) { ReaderPreferences(reader) { reader = it } }
        } } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            listOf("text" to "#123abc", "background" to "37bc91", "toolbar" to "f12345").forEach { (field, typed) ->
                compose.onNodeWithTag("reader-color-$field").performScrollTo().performTouchInput { click() }
                compose.onNodeWithTag("reader-color-input-mode").performTouchInput { click() }
                compose.onNodeWithTag("reader-color-input").assertIsDisplayed().assertIsFocused()
                compose.waitUntil(5_000) { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
                // 发送实际按键到已聚焦的输入框，不使用 SetText / performTextReplacement。
                instrumentation.sendStringSync(typed)
                compose.onNodeWithTag("reader-color-input").assertTextContains(typed)
                compose.onNodeWithText("应用颜色").assertIsDisplayed().assertIsEnabled()
                if(field == "text") saveTestScreenshot(if(eInk) "problem-reader-eink-manual-keyboard.png" else "problem-reader-manual-color-keyboard.png")
                compose.onNodeWithText("应用颜色").performTouchInput { click() }
            }
            compose.runOnIdle {
                assertEquals(0x123ABCL, reader.customColors.text)
                assertEquals(0x37BC91L, reader.customColors.background)
                assertEquals(0xF12345L, reader.customColors.toolbar)
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    @Test fun eInkPickerUsesLargeStepButtonsAndUpdatesThePlaneOnlyOnRelease() {
        var applied: Long? = null
        compose.setContent { AppInteractionMode(true, true) { NoveliaTheme("light") {
            ReaderColorPicker("字体颜色", 0xFF0000L, onDismiss = {}, onApply = { applied = it })
        } } }
        compose.onNodeWithContentDescription("减小色相").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("增大色相").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("reader-color-plane").performScrollTo().performTouchInput {
            down(Offset(width * .25f, height * .25f))
            moveTo(Offset(width * .75f, height * .5f))
        }
        compose.onNodeWithTag("reader-color-code-preview").assertTextEquals("#FF0000")
        compose.onNodeWithTag("reader-color-plane").performTouchInput { up() }
        compose.onNodeWithTag("reader-color-code-preview").assertTextEquals("#802020")
        saveTestScreenshot("problem-reader-eink-color-picker.png")
        compose.onNodeWithContentDescription("增大色相").performScrollTo().performTouchInput { click() }
        assertEquals(15f, compose.onNodeWithTag("reader-color-hue").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, .001f)
        compose.onNodeWithTag("reader-color-input-mode").performScrollTo().performClick()
        compose.onNodeWithTag("reader-color-input").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("reader-color-input").performTextReplacement("#102030")
        saveTestScreenshot("problem-reader-eink-color-input.png")
        compose.onNodeWithText("应用颜色").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0x102030L, applied) }
    }
}
