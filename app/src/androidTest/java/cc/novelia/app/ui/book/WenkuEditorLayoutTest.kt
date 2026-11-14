package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WenkuEditorLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun requiredFieldsAndFooterRemainUsableWithKeyboardAndNarrowLargeText() = withDraft { app, visible ->
        var theme by mutableStateOf("light")
        var narrow by mutableStateOf(false)
        var eInk by mutableStateOf(false)
        var square by mutableStateOf(false)
        var hideKeyboard: () -> Unit = {}
        compose.setContent {
            val density = LocalDensity.current
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            SideEffect { hideKeyboard = { focus.clearFocus(); keyboard?.hide() } }
            NoveliaTheme(theme, squareCorners = square) { AppInteractionMode(eInk, true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.width(if(narrow) 320.dp else 840.dp).fillMaxHeight().testTag("wenku-test-viewport")) {
                        CompositionLocalProvider(LocalDensity provides Density(density.density, if(narrow) 1.3f else 1f)) {
                            if(visible.value) WenkuForm(controller(app), null, WenkuDetail())
                        }
                    }
                }
            } }
        }
        compose.onNodeWithTag("wenku-save").assertIsDisplayed().assertIsNotEnabled()
        saveTestScreenshot("wenku-editor-light.png")
        compose.onNodeWithTag("wenku-title").performClick().performTextInput("原文作品")
        compose.onNodeWithTag("wenku-title-zh").performScrollTo().performTextInput("中文作品")
        compose.onNodeWithTag("wenku-save").assertIsDisplayed().assertIsEnabled()
        saveTestScreenshot("wenku-editor-keyboard.png")
        compose.runOnIdle { hideKeyboard() }
        compose.onNodeWithTag("wenku-cover").performScrollTo().performTextReplacement("http://example.com/cover.jpg")
        compose.onNodeWithTag("wenku-save").assertIsNotEnabled()
        compose.onNodeWithText("请使用 HTTPS 链接").assertExists()
        compose.onNodeWithTag("wenku-cover").performTextClearance()
        compose.runOnIdle { hideKeyboard() }
        compose.onNodeWithTag("wenku-save").assertIsEnabled()
        compose.onNodeWithTag("wenku-title").performScrollTo()
        compose.runOnIdle { theme = "dark" }
        compose.waitForIdle()
        saveTestScreenshot("wenku-editor-dark.png")
        compose.runOnIdle { theme = "light"; narrow = true }
        compose.onNodeWithTag("wenku-title").performScrollTo().assertIsDisplayed()
        val viewport = compose.onNodeWithTag("wenku-test-viewport").fetchSemanticsNode().boundsInRoot
        val field = compose.onNodeWithTag("wenku-title").fetchSemanticsNode().boundsInRoot
        assertTrue(field.left >= viewport.left && field.right <= viewport.right)
        compose.onNodeWithTag("wenku-save").assertIsDisplayed().assertIsEnabled()
        saveTestScreenshot("wenku-editor-narrow-large-text.png")
        compose.onNodeWithText("添加分卷").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("暂无出版分卷").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("wenku-save").assertIsDisplayed()
        saveTestScreenshot("wenku-editor-volumes-empty.png")
        compose.runOnIdle { eInk = true; square = true }
        compose.onNodeWithTag("wenku-save").assertIsDisplayed()
        saveTestScreenshot("wenku-editor-eink-square.png")
    }

    @Test fun publicationVolumeCanBeAddedEditedAndRemovedWithInlineDateValidation() = withDraft { app, visible ->
        var hideKeyboard: () -> Unit = {}
        compose.setContent {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            SideEffect { hideKeyboard = { focus.clearFocus(); keyboard?.hide() } }
            NoveliaTheme("light") { AppInteractionMode(false, true) {
                if(visible.value) WenkuForm(controller(app), null, WenkuDetail())
            } }
        }
        compose.onNodeWithText("添加分卷").performScrollTo().performClick()
        compose.onNodeWithText("保存分卷").assertIsNotEnabled()
        compose.onNodeWithTag("wenku-volume-asin").performTextInput("B000000001")
        compose.onNodeWithTag("wenku-volume-title").performScrollTo().performTextInput("原文第一卷")
        compose.onNodeWithTag("wenku-volume-date").performScrollTo().performTextInput("2026-02-30")
        compose.onNodeWithText("保存分卷").assertIsNotEnabled()
        compose.onNodeWithTag("wenku-volume-date").performTextReplacement("2026-02-28")
        compose.onNodeWithTag("wenku-volume-date").assertIsDisplayed()
        compose.onNodeWithText("保存分卷").assertIsEnabled().assertIsDisplayed()
        saveTestScreenshot("wenku-editor-volume-keyboard.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("wenku-volume-asin").performScrollTo()
        saveTestScreenshot("wenku-editor-volume-dialog.png")
        compose.onNodeWithText("保存分卷").performClick()
        compose.onNodeWithText("原文第一卷").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("编辑分卷").performClick()
        compose.onNodeWithTag("wenku-volume-title").performScrollTo().performTextReplacement("修订后的第一卷")
        compose.runOnIdle { hideKeyboard() }
        compose.onNodeWithText("保存分卷").performClick()
        compose.onNodeWithText("修订后的第一卷").performScrollTo().assertIsDisplayed()
        saveTestScreenshot("wenku-editor-volume-row.png")
        compose.onNodeWithContentDescription("移除分卷信息").performClick()
        compose.onNodeWithText("暂无出版分卷").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val saved = requireNotNull(app.store.state.value.drafts["wenku:new"])
        assertTrue(cc.novelia.app.data.storage.appJson.decodeFromString<WenkuDetail>(saved).volumes.isEmpty())
    }

    @Composable private fun controller(app: NoveliaApplication) = AppController(
        app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() },
    )

    private fun withDraft(block: (NoveliaApplication, MutableState<Boolean>) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val previous = app.store.state.value.drafts["wenku:new"]
        val visible = mutableStateOf(true)
        app.store.update { it.copy(drafts = it.drafts - "wenku:new") }
        try { block(app, visible) }
        finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            app.store.update { it.copy(drafts = (it.drafts - "wenku:new") + listOfNotNull(previous?.let { value -> "wenku:new" to value })) }
            runBlocking { app.store.flush() }
        }
    }
}
