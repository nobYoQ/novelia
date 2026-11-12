package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import cc.novelia.app.data.model.*
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.theme.ReaderNightTheme
import cc.novelia.app.ui.theme.LocalReaderBrightness
import cc.novelia.app.ui.theme.ApplyReaderWindowBrightness
import cc.novelia.app.ui.theme.activityOrNull
import cc.novelia.app.ui.components.base.AppDialog
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderNightPreferencesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun togglingNightThemeKeepsTheOpenPreferenceTab() {
        var reader by mutableStateOf(ReaderSettings())
        compose.setContent { NoveliaTheme("light") {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Button(onClick = { reader = reader.copy(nightReading = !reader.nightReading) }) { Text("切换夜间") }
                ReaderNightTheme(reader.nightReading) { ReaderPreferences(reader) { reader = it } }
            }
        } }
        compose.onNodeWithText("界面").performClick()
        compose.onNodeWithText("切换夜间").performClick()
        compose.onNodeWithText("阅读方向").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("文字与主题").assertDoesNotExist()
        compose.onNodeWithText("切换夜间").performClick()
        compose.onNodeWithText("阅读方向").performScrollTo().assertIsDisplayed()
    }

    @Test fun presetEditorCanCancelResetUndoAndSaveWithoutEnablingNightReading() {
        val ordinary = ReaderSettings(theme = "paper", fontSize = 24f, lineHeight = 1.6f,
            nightPreset = ReaderNightPreset(lineHeight = 2.3f, letterSpacing = .1f))
        var reader by mutableStateOf(ordinary)
        compose.setContent { NoveliaTheme("light") {
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) { ReaderPreferences(reader) { reader = it } }
        } }
        compose.onNodeWithText("夜间阅读").assertIsDisplayed()
        compose.onNodeWithText("自动").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("adaptive", reader.normalBrightness.mode) }
        val beforeEditing = reader
        compose.onNodeWithTag("reader-edit-night-preset").performScrollTo().performClick()
        compose.onNodeWithTag("reader-night-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("reader-reset-night-preset").performClick()
        compose.runOnIdle { assertEquals(beforeEditing, reader) }
        compose.onNodeWithTag("reader-night-editor-cancel").performClick()
        compose.onNodeWithTag("reader-night-preset-editor").assertDoesNotExist()
        compose.runOnIdle { assertEquals(beforeEditing, reader) }
        compose.onNodeWithTag("reader-edit-night-preset").performScrollTo().performClick()
        compose.onNodeWithTag("reader-reset-night-preset").performClick()
        compose.onNodeWithTag("reader-undo-night-reset").performClick()
        compose.onNodeWithTag("reader-night-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("reader-reset-night-preset").performClick()
        compose.onNodeWithTag("reader-night-editor-save").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertFalse(reader.nightReading)
            assertEquals(ReaderNightPreset(), reader.nightPreset)
            assertEquals(ordinary.theme, reader.theme)
            assertEquals(ordinary.lineHeight, reader.lineHeight, 0f)
        }
        compose.onNodeWithTag("reader-night-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("reader-font-settings").assertDoesNotExist()
        compose.onNodeWithTag("reader-night-toggle").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(reader.nightReading)
            assertEquals(24f, reader.readingAppearance().fontSize, 0f)
            assertEquals("paper", reader.readingAppearance().theme)
        }
    }

    @Test fun layoutShowsSeparateEntryPreviewGroupsAndPinnedActionsAtLargeFontSize() {
        var reader by mutableStateOf(ReaderSettings(nightReading = true))
        var scale by mutableFloatStateOf(Float.NaN)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if(scale.isNaN()) density.fontScale else scale)) {
                NoveliaTheme("light") { ReaderNightTheme(reader.nightReading) { CompositionLocalProvider(LocalReaderBrightness provides .1f) {
                    Surface(Modifier.fillMaxSize().safeDrawingPadding()) { ReaderPreferences(reader, perBook = false) { reader = it } }
                } } }
            }
        }
        compose.onNodeWithTag("reader-edit-night-preset").assertIsDisplayed()
        saveTestScreenshot("night-settings-common-dark.png")
        compose.onNodeWithTag("reader-edit-night-preset").performClick()
        compose.onNodeWithTag("reader-night-editor-save").assertIsDisplayed()
        compose.onNodeWithTag("reader-night-editor-cancel").assertIsDisplayed()
        compose.onNodeWithTag("reader-night-editor-close").assertIsDisplayed()
        compose.onNodeWithTag("reader-brightness-minimum").assertDoesNotExist()
        saveTestScreenshot("night-settings-editor-brightness.png")
        compose.onNodeWithText("配色", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("reader-night-preview").assertIsDisplayed()
        saveTestScreenshot("night-settings-editor-colors.png")
        compose.runOnIdle { scale = 1.3f }
        compose.onNodeWithText("排版", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("reader-night-editor-save").assertIsDisplayed()
        compose.onNodeWithTag("reader-night-editor-cancel").assertIsDisplayed()
        compose.onNodeWithTag("reader-reset-night-preset").assertIsDisplayed()
        compose.onNodeWithTag("reader-night-editor-close").assertIsDisplayed()
        saveTestScreenshot("night-settings-editor-type-large-top.png")
        compose.onNodeWithText("沿用普通字体").performScrollTo().performClick()
        compose.onNodeWithTag("reader-font-settings").performScrollTo().performClick()
        compose.onNodeWithText("预设预览").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("加粗文字").performScrollTo().performClick()
        compose.onNodeWithTag("reader-night-editor-save").assertIsEnabled()
        saveTestScreenshot("night-settings-editor-type-large.png")
        compose.onNodeWithTag("reader-night-editor-cancel").performClick()
        compose.runOnIdle { assertFalse(reader.nightPreset.weight); assertNull(reader.nightPreset.fontFamily); reader = reader.copy(nightReading = false); scale = 1f }
        saveTestScreenshot("night-settings-common-light.png")
    }

    @Test fun unsavedPresetSurvivesStateRestorationWithoutChangingOrdinarySettings() {
        val ordinary = ReaderSettings(fontSize = 24f, lineHeight = 1.6f, nightPreset = ReaderNightPreset(letterSpacing = .1f))
        var reader by mutableStateOf(ordinary)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NoveliaTheme("light") {
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) { ReaderPreferences(reader) { reader = it } }
        } }
        compose.onNodeWithTag("reader-edit-night-preset").performScrollTo().performClick()
        compose.onNodeWithTag("reader-reset-night-preset").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("reader-night-editor-save").assertIsEnabled()
        compose.runOnIdle { assertEquals(ordinary, reader) }
        compose.onNodeWithTag("reader-night-editor-save").performClick()
        compose.runOnIdle {
            assertFalse(reader.nightReading)
            assertEquals(ReaderNightPreset(), reader.nightPreset)
            assertEquals(24f, reader.fontSize, 0f)
            assertEquals(1.6f, reader.lineHeight, 0f)
        }
    }

    @Test fun nativePaginationAppliesLetterSpacingToPlainAndRubyText() {
        val plain = cc.novelia.app.reader.ReadingParagraph(0, listOf(cc.novelia.app.reader.TextPart("夜间阅读字距测试".repeat(15), "中文")))
        val settings = ReaderSettings(fontSize = 20f, indent = false)
        val normal = measureEInkChapter(listOf(plain), settings, 220, 600, Density(1f))
        val spaced = measureEInkChapter(listOf(plain), settings.copy(letterSpacing = .15f), 220, 600, Density(1f))
        assertTrue(spaced.layouts.getValue(0).lineCount > normal.layouts.getValue(0).lineCount)
        assertEquals(.15f, spaced.layouts.getValue(0).paint.letterSpacing, 0f)
        val ruby = plain.copy(parts = listOf(plain.parts.single().copy(inline = listOf(InlineContent(0, 2, InlineKind.Ruby, "やかん")))))
        val rich = measureEInkChapter(listOf(ruby), settings.copy(letterSpacing = .15f), 220, 600, Density(1f))
        assertEquals(.15f, rich.layouts.getValue(0).paint.letterSpacing, 0f)
    }

    @Test fun dialogUsesReaderBrightnessAndLeavingRestoresOriginalWindowValue() {
        var visible by mutableStateOf(true)
        var brightness by mutableFloatStateOf(.12f)
        var activityWindow: android.view.Window? = null
        var dialogWindow: android.view.Window? = null
        var original = -1f
        compose.setContent {
            val window = LocalContext.current.activityOrNull()!!.window
            val before = remember(window) { window.attributes.screenBrightness }
            SideEffect { activityWindow = window; original = before }
            if(visible) CompositionLocalProvider(LocalReaderBrightness provides brightness) {
                ApplyReaderWindowBrightness(window)
                AppDialog({}) {
                    val dialog = (LocalView.current.parent as DialogWindowProvider).window
                    SideEffect { dialogWindow = dialog }
                    Text("夜间亮度弹窗")
                }
            }
        }
        compose.runOnIdle {
            assertEquals(.12f, activityWindow!!.attributes.screenBrightness, 0f)
            assertEquals(.12f, dialogWindow!!.attributes.screenBrightness, 0f)
            brightness = .2f
        }
        compose.runOnIdle {
            assertEquals(.2f, dialogWindow!!.attributes.screenBrightness, 0f)
            visible = false
        }
        compose.runOnIdle { assertEquals(original, activityWindow!!.attributes.screenBrightness, 0f) }
    }
}
