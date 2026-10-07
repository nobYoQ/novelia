package cc.novelia.app.ui.book

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookDetailSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipingAcrossTheMidpointKeepsBothPagesOpaque() {
        showAnimatedPanels()
        val pager = compose.onNodeWithTag("book-detail-pager")
        pager.performTouchInput { down(Offset(width * .9f, centerY)) }
        for(fraction in listOf(.7f, .5f, .3f, .1f)) {
            pager.performTouchInput { moveTo(Offset(width * fraction, centerY), delayMillis = 64) }
            assertPagerOpaque()
        }
        pager.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("目录").assertIsSelected()
        compose.mainClock.autoAdvance = false
        pager.performTouchInput { down(Offset(width * .1f, centerY)) }
        for(fraction in listOf(.3f, .5f, .7f, .9f)) {
            pager.performTouchInput { moveTo(Offset(width * fraction, centerY), delayMillis = 64) }
            assertPagerOpaque()
        }
        pager.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("简介").assertIsSelected()
    }

    @Test fun animatedTabClicksKeepContentOpaqueIncludingIntermediatePages() {
        showAnimatedPanels()
        for(title in listOf("讨论", "简介")) {
            compose.onNodeWithText(title).performClick()
            repeat(12) { assertPagerOpaque() }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithText(title).assertIsSelected()
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
        }
        compose.mainClock.autoAdvance = true
    }

    private fun showAnimatedPanels() {
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = false) {
                NoveliaTheme("light") {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    AdaptiveBookDetail(tab, { tab = it }, listOf("简介", "目录", "讨论"), rememberSaveableStateHolder(),
                        Modifier.size(320.dp, 400.dp).background(Color.White)) {
                        // 各页使用相同实色，滑动任何位置都不应因透明度重置而露出白底。
                        Box(Modifier.fillMaxSize().background(Color.Blue))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun assertPagerOpaque() {
        compose.mainClock.advanceTimeBy(32)
        val frame = compose.onNodeWithTag("book-detail-pager").captureToImage().toPixelMap()
        for(x in listOf(frame.width / 8, frame.width * 3 / 8, frame.width * 5 / 8, frame.width * 7 / 8)) {
            for(y in listOf(1, frame.height / 2, frame.height - 2)) {
                assertEquals("滑动中途不应重新淡入或上浮（$x, $y）", Color.Blue, frame[x, y])
            }
        }
    }

    @Test fun swipesAndTabClicksKeepInputAcrossResizeAndRecreation() {
        var selected = 0
        var width by mutableStateOf(390.dp)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppInteractionMode(eInk = false, reducedMotion = true) {
                NoveliaTheme("light") {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    SideEffect { selected = tab }
                    AdaptiveBookDetail(tab, { tab = it }, listOf("简介", "目录", "讨论"), rememberSaveableStateHolder(),
                        Modifier.requiredWidth(width).height(480.dp)) { page ->
                        var input by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("page-$page"))
                    }
                }
            }
        }
        compose.onNodeWithTag("page-0").performTextInput("简介位置")
        compose.onNodeWithTag("book-detail-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("page-1").assertIsDisplayed().performTextInput("目录搜索")
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithTag("book-detail-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("page-2").assertIsDisplayed()
        compose.onNodeWithTag("book-detail-pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("page-1").assertTextContains("目录搜索")
        compose.onNodeWithText("简介").performClick()
        compose.onNodeWithTag("page-0").assertTextContains("简介位置")
        compose.runOnIdle { width = 1000.dp }
        compose.onNodeWithTag("book-detail-dual-pane").assertExists()
        compose.runOnIdle { width = 390.dp }
        compose.onNodeWithTag("page-0").assertIsDisplayed().assertTextContains("简介位置")
        compose.onNodeWithText("目录").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("page-1").assertIsDisplayed().assertTextContains("目录搜索")
    }
}
