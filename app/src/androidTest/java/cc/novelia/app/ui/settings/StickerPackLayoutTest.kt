package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
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
import cc.novelia.app.R
import cc.novelia.app.data.stickers.StickerSlot
import cc.novelia.app.ui.feedback.StickerPacks
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** 覆盖实际管理页到预览、图片面板、名称和选色器的路径，同时留存布局验收图。 */
class StickerPackLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collectionPreviewAndEditingStayUsableAcrossThemesAndNarrowLargeText() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val store = app.stickerPacks
        val previousId = app.store.state.value.stickerPackId
        val previousDrafts = store.state.value.drafts.map { it.id }.toSet()
        val pack = runBlocking {
            val draft = store.beginDraft()
            store.addImages(draft.id, listOf(
                StickerSlot.Neutral to { app.resources.openRawResource(R.drawable.midori_neutral) },
                StickerSlot.Reading to { app.resources.openRawResource(R.drawable.midori_reading) },
            ))
            store.saveDraft(draft.id, "午后阅读", "午后", "#E15C86")
        }
        var theme by mutableStateOf("light")
        var narrow by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        var eInk by mutableStateOf(false)
        var hideKeyboard: () -> Unit = {}
        try {
            app.store.update { it.copy(stickerPackId = StickerPacks.default.id) }
            compose.setContent {
                val density = LocalDensity.current
                val focus = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                SideEffect { hideKeyboard = { focus.clearFocus(); keyboard?.hide() } }
                NoveliaTheme(theme) { AppInteractionMode(eInk, true) {
                    Surface {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.width(if(narrow) 320.dp else 840.dp).fillMaxHeight().testTag("sticker-test-viewport")) {
                                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                                    StickerPackScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }))
                                }
                            }
                        }
                    }
                } }
            }
            find("sticker-pack-${StickerPacks.default.id}").assertIsSelected()
            compose.onNodeWithText("星川绿@Hoshikawa Midori").assertIsDisplayed()
            top()
            screenshot("sticker-collection-light.png")
            find("sticker-preview-${StickerPacks.default.id}").performClick()
            compose.onNodeWithText("全部表情").assertIsDisplayed()
            compose.onNodeWithText("星川绿@Hoshikawa Midori").assertIsDisplayed()
            screenshot("sticker-preview-built-in.png")
            compose.onNodeWithContentDescription("返回").performClick()

            find("sticker-pack-${pack.id}").performClick().assertIsSelected()
            find("sticker-preview-${pack.id}").performClick()
            screenshot("sticker-preview-custom.png")
            compose.onNodeWithText("编辑贴纸集").performClick()
            find("sticker-choose-neutral").assertIsDisplayed()
            screenshot("sticker-editor-images.png")
            find("sticker-choose-happy").performClick()
            compose.onNodeWithText("开心表情").assertIsDisplayed()
            compose.onNodeWithText("选择图片").assertIsDisplayed()
            screenshot("sticker-editor-image-panel.png")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("名称与配色").performClick()
            compose.onNodeWithText("基本信息").assertIsDisplayed()
            compose.onNodeWithTag("sticker-pack-title").performTextReplacement("午后阅读 · 新版")
            compose.runOnIdle { hideKeyboard() }
            screenshot("sticker-editor-details.png")
            find("sticker-pack-heart-color").performClick()
            compose.onNodeWithText("应用颜色").assertIsDisplayed()
            screenshot("sticker-editor-color-picker.png")
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("sticker-pack-save").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { store.state.value.packs.first { it.id == pack.id }.manifest.title == "午后阅读 · 新版" }
            compose.onNodeWithText("全部表情").assertIsDisplayed()
            assertEquals(pack.id, app.store.state.value.stickerPackId)
            compose.onNodeWithContentDescription("返回").performClick()
            top()
            compose.runOnIdle { theme = "dark" }
            compose.waitForIdle()
            assertTrue(compose.onNodeWithTag("sticker-test-viewport").captureToImage().toPixelMap()[2, 2].luminance() < .5f)
            screenshot("sticker-collection-dark.png")
            compose.runOnIdle { theme = "light"; narrow = true; fontScale = 1.3f }
            find("sticker-pack-${StickerPacks.default.id}").assertIsDisplayed()
            val viewport = compose.onNodeWithTag("sticker-test-viewport").fetchSemanticsNode().boundsInRoot
            val card = compose.onNodeWithTag("sticker-pack-${StickerPacks.default.id}").fetchSemanticsNode().boundsInRoot
            assertTrue(card.left >= viewport.left && card.right <= viewport.right)
            screenshot("sticker-collection-narrow-large-text.png")
            find("sticker-preview-${StickerPacks.default.id}").performClick()
            compose.onNodeWithText("星川绿@Hoshikawa Midori").assertIsDisplayed()
            screenshot("sticker-preview-narrow-large-text.png")
            compose.onNodeWithContentDescription("返回").performClick()
            find("sticker-pack-create").performClick()
            find("sticker-choose-neutral").assertIsDisplayed()
            compose.onNodeWithTag("sticker-pack-save").assertIsNotEnabled()
            top()
            screenshot("sticker-editor-narrow-large-text.png")
            compose.onNodeWithText("名称与配色").performClick()
            compose.onNodeWithText("基本信息").assertIsDisplayed()
            compose.onNodeWithTag("sticker-pack-title").assertIsDisplayed()
            screenshot("sticker-details-narrow-large-text.png")
            compose.onNodeWithContentDescription("返回").performClick()
            top()
            compose.runOnIdle { narrow = false; fontScale = 1f; eInk = true }
            screenshot("sticker-collection-eink.png")
        } finally {
            runBlocking {
                store.state.value.drafts.filter { it.id !in previousDrafts }.forEach { store.discardDraft(it.id) }
                store.remove(pack.id)
            }
            app.store.update { it.copy(stickerPackId = previousId) }
        }
    }

    private fun top() { compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0); compose.waitForIdle() }

    private fun screenshot(name: String) { compose.waitForIdle(); saveTestScreenshot(name) }

    private fun find(tag: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
}
