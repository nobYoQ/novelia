package cc.novelia.app.ui.reader

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
        compose.onNodeWithContentDescription("背景颜色 #141A16").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0x141A16L, reader.customColors.background)
            assertEquals(ReaderCustomColors().text, reader.customColors.text)
        }
        compose.onNodeWithTag("reader-color-background").assertTextContains("#141A16")
        compose.onNodeWithContentDescription("字体颜色 #DDE5DC").performScrollTo().performClick()
        compose.onNodeWithContentDescription("工具栏颜色 #141A16").performScrollTo().performClick()
        compose.onNodeWithTag("reader-color-preview").performScrollTo()
        saveTestScreenshot("problem-reader-custom-colors.png")
    }
}
