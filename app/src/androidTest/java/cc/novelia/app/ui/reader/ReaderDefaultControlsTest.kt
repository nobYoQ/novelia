package cc.novelia.app.ui.reader

import android.graphics.Bitmap
import android.animation.ValueAnimator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReaderDefaultControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineDefaultsStayVisibleAndResetOnlyTheirOwnSetting() {
        val defaults = ReaderSettings(fontSize = 16.629105f, theme = "paper", lineHeight = 1.7f)
        var settings by mutableStateOf(defaults.copy(fontSize = 22f, theme = "dark", lineHeight = 2.1f))
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("dark") {
                Surface(Modifier.requiredWidth(360.dp).fillMaxHeight().testTag("reader-defaults-preview")) {
                    ReaderPreferences(settings, true, defaultSettings = defaults) { settings = it }
                }
            } }
        }
        capture("ui-refinement-reader-defaults.png")
        compose.onNodeWithTag("reader-default-字号").performScrollTo().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(defaults.fontSize, settings.fontSize, 0f); assertEquals(2.1f, settings.lineHeight, 0f) }
        compose.onNodeWithTag("reader-default-行距").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(defaults.lineHeight, settings.lineHeight, 0f); assertEquals("dark", settings.theme) }
        compose.onNodeWithText("纸张").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "默认选项"))
        capture("ui-refinement-reader-choices.png")
        compose.onNodeWithText("纸张").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("paper", settings.theme); assertEquals(defaults.fontSize, settings.fontSize, 0f) }
    }

    @Test fun defaultProgressCanRestoreEndpointsAndEInkUsesTheSameExactValue() {
        var value by mutableFloatStateOf(20f)
        var default by mutableFloatStateOf(14f)
        var eink by mutableStateOf(false)
        compose.setContent {
            AppInteractionMode(eink, true) { NoveliaTheme("light") {
                Surface(Modifier.width(280.dp)) {
                    ReaderSlider("字号 ${value.toInt()}", value, 14f..32f, defaultValue = default) { value = it }
                }
            } }
        }
        compose.onNodeWithTag("reader-default-字号").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(14f, value, 0f); default = 32f }
        compose.onNodeWithTag("reader-default-字号").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(32f, value, 0f); eink = true; default = 16.629105f }
        compose.onNodeWithTag("reader-default-字号").performClick()
        compose.runOnIdle { assertEquals(default, value, 0f) }
    }

    @Test fun referenceProgressKeepsTheOriginalHeightAndUsesTwoShades() {
        var theme by mutableStateOf("dark")
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme(theme) {
                Surface(Modifier.width(360.dp).testTag("progress-preview")) {
                    Column {
                        Box(Modifier.testTag("less-row")) { ReaderSlider("较少 30", 30f, 0f..100f, defaultValue = 60f) {} }
                        Box(Modifier.testTag("more-row")) { ReaderSlider("较多 80", 80f, 0f..100f, defaultValue = 60f) {} }
                        Box(Modifier.testTag("plain-row")) { ReaderSlider("原始 30", 30f, 0f..100f) {} }
                    }
                }
            } }
        }
        val height = compose.onNodeWithTag("plain-row").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithTag("less-row").assertHeightIsEqualTo(height)
        compose.onNodeWithTag("more-row").assertHeightIsEqualTo(height)
        fun checkShades() {
            val less = compose.onNodeWithTag("reader-track-较少", useUnmergedTree = true).captureToImage().toPixelMap()
            val more = compose.onNodeWithTag("reader-track-较多", useUnmergedTree = true).captureToImage().toPixelMap()
            fun sample(pixels: androidx.compose.ui.graphics.PixelMap, fraction: Float) = pixels[(pixels.width * fraction).toInt(), pixels.height / 2]
            assertNotEquals("默认余量需要区别于未完成轨道", sample(less, .45f), sample(less, .8f))
            assertNotEquals("默认余量需要区别于当前进度", sample(less, .45f), sample(less, .15f))
            assertTrue("默认范围应比额外的当前进度颜色更深", sample(more, .2f).luminance() < sample(more, .7f).luminance())
        }
        checkShades()
        capture("reader-default-progress-dark.png", "progress-preview")
        compose.runOnIdle { theme = "light" }
        checkShades()
        capture("reader-default-progress-light.png", "progress-preview")
    }

    @Test fun resetAnimatesWithoutIntermediateWritesAndReducedMotionStopsIt() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        var value by mutableFloatStateOf(80f)
        var reduced by mutableStateOf(false)
        val writes = mutableListOf<Float>()
        compose.setContent {
            AppInteractionMode(false, reduced) { NoveliaTheme("dark") {
                Surface(Modifier.width(360.dp)) {
                    ReaderSlider("动画", value, 0f..100f, modifier = Modifier.testTag("progress"), defaultValue = 40f, livePreviewStep = 1f) {
                        writes += it
                        value = it
                    }
                }
            } }
        }
        fun progress() = compose.onNodeWithTag("progress").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("reader-default-动画").performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle { assertEquals(listOf(40f), writes) }
            assertTrue("复位需要经过中间位置", progress() > 40f && progress() < 80f)
            compose.mainClock.advanceTimeBy(400)
            assertEquals(40f, progress(), 0f)
            compose.runOnIdle { value = 80f; writes.clear() }
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithTag("reader-default-动画").performClick()
            compose.mainClock.advanceTimeBy(80)
            assertTrue(progress() > 40f)
            compose.runOnIdle { reduced = true }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("打开减少动效立即完成复位", 40f, progress(), 0f)
            compose.runOnIdle { reduced = false }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("重新启用动效不能重播复位", 40f, progress(), 0f)
            compose.runOnIdle { reduced = true; value = 80f; writes.clear() }
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithTag("reader-default-动画").performTouchInput { click(center) }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(40f, progress(), 0f)
            compose.runOnIdle { assertEquals(listOf(40f), writes) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun crossingTheDefaultCrossfadesAndReducedMotionFinishesTheColor() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        var value by mutableFloatStateOf(80f)
        var reduced by mutableStateOf(false)
        compose.setContent {
            AppInteractionMode(false, reduced) { NoveliaTheme("dark") {
                Surface(Modifier.width(360.dp)) { ReaderSlider("颜色", value, 0f..100f, defaultValue = 40f) { value = it } }
            } }
        }
        fun color(): Color {
            val pixels = compose.onNodeWithTag("reader-track-颜色", useUnmergedTree = true).captureToImage().toPixelMap()
            return pixels[pixels.width / 10, pixels.height / 2]
        }
        val dark = color()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { value = 20f }
            compose.mainClock.advanceTimeBy(80)
            val between = color()
            compose.runOnIdle { reduced = true }
            compose.mainClock.advanceTimeByFrame()
            val light = color()
            assertTrue("跨过默认位置应渐变到普通进度色", dark.luminance() < between.luminance() && between.luminance() < light.luminance())
            compose.runOnIdle { reduced = false }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(light, color())
            compose.runOnIdle { value = 80f }
            compose.mainClock.advanceTimeBy(80)
            val reverse = color()
            assertTrue("返回较大值时同样需要渐变", reverse.luminance() > dark.luminance() && reverse.luminance() < light.luminance())
            compose.mainClock.advanceTimeBy(400)
            assertEquals(dark, color())
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun draggingAndScrollingFromDefaultProgressDoNotTriggerReset() {
        var value by mutableFloatStateOf(80f)
        var reference by mutableFloatStateOf(40f)
        val writes = mutableListOf<Float>()
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.setContent {
            AppInteractionMode(false, true) { NoveliaTheme("dark") {
                scroll = rememberScrollState()
                Surface(Modifier.width(360.dp).height(400.dp)) {
                    Column(Modifier.verticalScroll(scroll)) {
                        ReaderSlider("手势", value, 0f..100f, modifier = Modifier.testTag("drag-slider"), defaultValue = reference) { writes += it; value = it }
                        repeat(20) { Text("阅读设置 $it", Modifier.padding(20.dp)) }
                    }
                }
            } }
        }
        compose.onNodeWithTag("drag-slider").performTouchInput { swipe(Offset(width * .15f, centerY), Offset(width * .9f, centerY), 300) }
        compose.runOnIdle { assertTrue(value > 85f); assertFalse(writes.contains(40f)); value = 20f; reference = 60f; writes.clear() }
        compose.onNodeWithTag("drag-slider").performTouchInput { swipe(Offset(width * .5f, centerY), Offset(width * .15f, centerY), 300) }
        compose.runOnIdle { assertTrue(value < 25f); assertFalse(writes.contains(60f)); writes.clear() }
        compose.onNodeWithTag("reader-default-手势").performTouchInput { swipe(center, center - Offset(0f, 160f), 300) }
        compose.runOnIdle { assertTrue(scroll.value > 0); assertTrue(writes.isEmpty()) }
    }

    private fun capture(name: String, tag: String = "reader-defaults-preview") {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
