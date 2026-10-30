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
import cc.novelia.app.reader.ReaderTapAction
import cc.novelia.app.reader.ReaderTapZoneMap
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

    private fun feedback(map: ReaderTapZoneMap = ReaderTapZoneMap.Default) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides reduced) {
                    navigation = rememberReaderTapNavigation(enabled, onToggleMenu = { menus++ }, onPage = { pages++ }, zoneMap = map)
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

    @Test fun resizedMergedRegionHighlightsOnlyItsActualRowsAndColumns() {
        val map = ReaderTapZoneMap.Default.withColumnBoundary(1, .2f).withRowBoundary(1, .25f)
            .withRowBoundary(2, .8f).withAction(1, 1, false, ReaderTapAction.NextPage)
        feedback(map)
        try {
            val body = compose.onNodeWithTag("feedback")
            body.performTouchInput { click(Offset(width * .4f, height * .5f)) }
            compose.mainClock.advanceTimeBy(32)
            val pixels = body.captureToImage().toPixelMap()
            fun brightness(x: Float, y: Float) = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()].luminance()
            assertTrue(brightness(.25f, .5f) < .98f)
            assertTrue(brightness(.85f, .5f) < .98f)
            assertEquals(1f, brightness(.15f, .5f), .005f)
            assertEquals(1f, brightness(.85f, .1f), .005f)
            assertEquals(1f, brightness(.85f, .9f), .005f)
            compose.runOnIdle { assertEquals(1, pages) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun highlightIdentifiesEachConfirmedZoneAndFadesWithoutInterceptingInput() {
        feedback()
        val body = compose.onNodeWithTag("feedback")
        try {
            for((index, fraction) in listOf(.15f, .5f, .85f).withIndex()) {
                body.performTouchInput { click(Offset(width * fraction, centerY)) }
                compose.mainClock.advanceTimeBy(32)
                val during = samples()
                if(index == 1) assertEquals(1f, during[index], .005f) else assertTrue("点击区域应高亮", during[index] < .98f)
                during.forEachIndexed { other, value -> if(other != index) assertEquals(1f, value, .005f) }
                if(index != 1) {
                    val pixels = body.captureToImage().toPixelMap()
                    val edgeX = if(index == 0) 1 else pixels.width - 2
                    assertEquals("区域边缘不应出现描边", during[index], pixels[edgeX, pixels.height / 2].luminance(), .005f)
                }
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
            assertTrue("深色页面只应有轻微高亮", initial in .001f.. .02f)
            val darkPixels = body.captureToImage().toPixelMap()
            assertEquals(initial, darkPixels[darkPixels.width - 2, darkPixels.height / 2].luminance(), .005f)
            compose.mainClock.advanceTimeBy(32)
            assertEquals(initial, samples().last(), .001f)
            compose.mainClock.advanceTimeBy(300)
            compose.waitUntil(3_000) { navigation.feedback == null }
            compose.runOnIdle { dark = false; eInk = true }
            compose.mainClock.advanceTimeByFrame()
            body.performTouchInput { click(Offset(width * .15f, centerY)) }
            compose.mainClock.advanceTimeBy(32)
            val pixels = body.captureToImage().toPixelMap()
            val center = pixels[pixels.width / 6, pixels.height / 2].luminance()
            assertTrue("电子纸也使用淡色区域反馈", center in .8f.. .98f)
            assertEquals("电子纸反馈不应描边", center, pixels[1, pixels.height / 2].luminance(), .005f)
            compose.mainClock.advanceTimeBy(300)
            compose.waitUntil(3_000) { navigation.feedback == null }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun guideKeepsCloseVisibleWithLargeTextInAShortViewport() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    ReaderTapTutorial(true, false, WindowInsets(0, 0, 0, 0), 28.dp,
                        Modifier.size(720.dp, 240.dp)) { dismissed = true }
                }
            }
        }
        compose.onNodeWithTag("tap-zone-region-3").assertIsDisplayed().assertTextContains("上一页")
        compose.onNodeWithText("工具栏").assertIsDisplayed()
        compose.onNodeWithTag("tap-zone-region-5").assertIsDisplayed().assertTextContains("下一页")
        compose.onNodeWithContentDescription("关闭点击翻页引导").assertHeightIsAtLeast(48.dp).performTouchInput {
            down(center)
            moveBy(Offset(1f, 1f))
            up()
        }
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
