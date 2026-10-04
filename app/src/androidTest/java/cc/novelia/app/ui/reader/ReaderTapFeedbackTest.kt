package cc.novelia.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderTapFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var navigation: ReaderTapNavigation
    private var reduced by mutableStateOf(false)
    private var eInk by mutableStateOf(false)
    private var enabled by mutableStateOf(true)
    private var dark by mutableStateOf(false)
    private var menus = 0
    private var pages = 0

    private fun feedback() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides reduced) {
                    navigation = rememberReaderTapNavigation(enabled, onToggleMenu = { menus++ }, onPage = { pages++ })
                    Box(Modifier.size(300.dp, 180.dp).testTag("feedback").background(if(dark) Color.Black else Color.White)
                        .readerTapFeedback(navigation, if(dark) Color.White else Color.Black, eInk).readerTapNavigation(navigation))
                }
            }
        }
        compose.mainClock.autoAdvance = false
    }

    private fun samples(): List<Float> {
        val pixels = compose.onNodeWithTag("feedback").captureToImage().toPixelMap()
        return listOf(.15f, .5f, .85f).map { pixels[(pixels.width * it).toInt(), pixels.height / 2].luminance() }
    }

    @Test fun highlightIdentifiesEachConfirmedZoneAndFadesWithoutInterceptingInput() {
        feedback()
        val body = compose.onNodeWithTag("feedback")
        try {
            for((index, fraction) in listOf(.15f, .5f, .85f).withIndex()) {
                body.performTouchInput { click(Offset(width * fraction, centerY)) }
                compose.mainClock.advanceTimeBy(32)
                val during = samples()
                assertTrue("点击区域应高亮", during[index] < .98f)
                during.forEachIndexed { other, value -> if(other != index) assertEquals(1f, value, .005f) }
                compose.mainClock.advanceTimeBy(400)
                samples().forEach { assertEquals(1f, it, .005f) }
            }
            compose.runOnIdle { assertEquals(1, menus); assertEquals(2, pages) }
            body.performClick()
            body.performTouchInput { swipeLeft(); longClick(); down(center); cancel() }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { assertNull(navigation.feedback); assertEquals(2, menus); assertEquals(2, pages) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun repeatedTapsReplaceThePreviousRegionAndDisablingClearsTheFeedback() {
        feedback()
        val body = compose.onNodeWithTag("feedback")
        try {
            body.performTouchInput { click(Offset(width * .15f, centerY)) }
            compose.mainClock.advanceTimeBy(32)
            body.performTouchInput { click(Offset(width * .85f, centerY)) }
            compose.mainClock.advanceTimeBy(32)
            val during = samples()
            assertEquals(1f, during.first(), .005f)
            assertTrue(during.last() < .98f)
            compose.runOnIdle { enabled = false }
            compose.mainClock.advanceTimeBy(32)
            samples().forEach { assertEquals(1f, it, .005f) }
            body.performTouchInput { click(Offset(width * .85f, centerY)) }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnIdle { assertNull(navigation.feedback); assertEquals(1, menus); assertEquals(2, pages) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun reducedMotionAndEInkUseStaticFeedbackAndDarkPagesRemainVisible() {
        reduced = true
        dark = true
        feedback()
        val body = compose.onNodeWithTag("feedback")
        try {
            body.performTouchInput { click(Offset(width * .85f, centerY)) }
            compose.mainClock.advanceTimeBy(32)
            val initial = samples().last()
            assertTrue(initial > .005f)
            compose.mainClock.advanceTimeBy(32)
            assertEquals(initial, samples().last(), .001f)
            compose.mainClock.advanceTimeBy(300)
            compose.waitUntil(3_000) { navigation.feedback == null }
            compose.runOnIdle { dark = false; eInk = true }
            compose.mainClock.advanceTimeByFrame()
            body.performTouchInput { click(Offset(width * .15f, centerY)) }
            compose.mainClock.advanceTimeBy(32)
            val pixels = body.captureToImage().toPixelMap()
            assertTrue(pixels[1, pixels.height / 2].luminance() < .1f)
            assertEquals(1f, pixels[pixels.width / 6, pixels.height / 2].luminance(), .005f)
            compose.mainClock.advanceTimeBy(300)
            compose.waitUntil(3_000) { navigation.feedback == null }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun guideKeepsCloseVisibleWithLargeTextInAShortViewport() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    ReaderTapTutorial(true, false, WindowInsets(0, 0, 0, 0), 720f, 28.dp,
                        Modifier.size(720.dp, 240.dp)) { dismissed = true }
                }
            }
        }
        compose.onNodeWithText("上一页").assertIsDisplayed()
        compose.onNodeWithText("工具栏").assertIsDisplayed()
        compose.onNodeWithText("下一页").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭点击翻页引导").assertHeightIsAtLeast(48.dp).performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            up()
        }
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
