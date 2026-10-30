package cc.novelia.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.theme.LocalReducedMotion
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderTapZoneOverlayTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(tag: String, name: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("screenshots")!!
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun resizeMergeSaveAndCancelUseTheReaderOverlay() {
        var saved: ReaderTapZoneMap? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFFF8F4E9))) {
                    Text("山间的旅人\n\n" + "清晨的风吹过林间，远处的小镇渐渐出现在眼前。旅人停下脚步，翻开随身的书。\n\n".repeat(30),
                        Modifier.padding(24.dp), color = Color(0xFF35372F), style = MaterialTheme.typography.bodyLarge)
                    ReaderTapZoneEditor(ReaderTapZoneMap.Default, { saved = it }, { dismissed = true })
                }
            }
        }
        screenshot("tap-zone-editor", "pr2-zones-default")
        compose.onNodeWithTag("tap-zone-grid").performTouchInput {
            swipe(Offset(width / 3f, height * .43f), Offset(width * .2f, height * .43f), 600)
        }
        compose.onNodeWithTag("tap-zone-region-4").performClick()
        compose.onNodeWithTag("tap-zone-option-NextPage").performClick()
        compose.onNodeWithTag("tap-zone-region-5").assertDoesNotExist()
        screenshot("tap-zone-editor", "pr2-zones-resized-merged")
        compose.onNodeWithTag("tap-zone-save").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertTrue("触摸拖动应改变列宽", saved!!.columnEdges[1] < .3f)
            assertEquals(listOf(4, 5), saved!!.regionFor(4).cells)
            assertFalse(dismissed)
        }
        compose.onNodeWithTag("tap-zone-individual").performClick()
        compose.onNodeWithTag("tap-zone-region-5").assertExists()
        compose.onNodeWithTag("tap-zone-region-5").performClick()
        compose.onNodeWithTag("tap-zone-option-None").performClick()
        compose.onNodeWithTag("tap-zone-row-1").performSemanticsAction(SemanticsActions.SetProgress) { it(.08f) }
        compose.onNodeWithContentDescription("隐藏配置控件以编辑边缘区域").performClick()
        compose.onNodeWithTag("tap-zone-grid").performTouchInput { click(Offset(width * .1f, height * .04f)) }
        compose.onNodeWithTag("tap-zone-option-None").performScrollTo().performClick()
        compose.onNodeWithText("显示配置控件").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals(ReaderTapAction.NextPage, saved!!.singleAt(2, 1)) }
    }

    @Test fun tutorialUsesCustomBoundariesAndDoubleActions() {
        val map = ReaderTapZoneMap.Default.withColumnBoundary(1, .2f).withRowBoundary(2, .8f)
            .withAction(1, 1, false, ReaderTapAction.Navigate)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFFF8F4E9))) {
                    Text("阅读正文\n\n" + "溪水沿着山路流淌，白云慢慢经过天空。\n\n".repeat(30), Modifier.padding(24.dp))
                    ReaderTapTutorial(true, false, WindowInsets(0, 0, 0, 0), 0.dp,
                        Modifier.fillMaxSize(), map) {}
                }
            }
        }
        compose.onNodeWithTag("tap-zone-region-4").assertTextContains("目录")
        screenshot("reader-tap-tutorial", "pr2-zones-tutorial")
        compose.onNodeWithContentDescription("隐藏教程控件以查看完整分区").performClick()
        compose.onNodeWithTag("tap-zone-region-6").assertIsDisplayed().assertTextContains("上一页")
        screenshot("reader-tap-tutorial", "pr2-zones-tutorial-full")
        compose.onNodeWithText("显示教程控件").performClick()
        compose.onNodeWithText("双击").performClick()
        compose.onNodeWithTag("tap-zone-region-0").assertTextContains("目录")
    }

    @Test fun draggingRevealsNeighborTextAndReducedMotionCommitsImmediately() {
        val state = EInkPageState(null)
        var reduced by mutableStateOf(false)
        val settings = ReaderSettings(paginationMode = "auto", tapPageTurn = true, horizontalPageTurn = true, scrollPageTurn = true)
        val paragraphs = listOf(ReadingParagraph(0, listOf(TextPart("阅读正文沿着页面排列，下一页应在拖动时露出。".repeat(400), "gpt"))))
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides reduced) {
                    EInkPage(paragraphs, settings, state, Modifier.size(300.dp, 420.dp).testTag("motion-page"),
                        imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = { state.move(it) })
                }
            }
        }
        compose.waitUntil(10_000) { state.ready && state.pages.size > 1 }
        val page = compose.onNodeWithTag("motion-page")
        page.performTouchInput { down(Offset(width * .8f, height * .5f)); moveBy(Offset(-width * .5f, 0f), 300) }
        val pixels = page.captureToImage().toPixelMap()
        var ink = 0
        for(x in pixels.width * 3 / 4 until pixels.width - 6) for(y in 20 until pixels.height - 20) {
            if(pixels[x, y].luminance() < .5f) ink++
        }
        assertTrue("露出的相邻页应包含文字而不是空白背景", ink > 100)
        screenshot("motion-page", "pr2-page-drag")
        page.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, state.pageIndex); reduced = true }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            page.performTouchInput { swipeUp() }
            compose.runOnIdle { assertEquals(2, state.pageIndex) }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
