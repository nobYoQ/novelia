package cc.novelia.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** 实际正文的默认 indication 不能叠加到分区反馈上；空 Box 的反馈测试无法覆盖这一层。 */
class ReaderBodyFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val settings = ReaderSettings(paginationMode = "auto", tapPageTurn = true)
    private val illustration = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply {
        eraseColor(android.graphics.Color.rgb(176, 176, 176))
    }

    private fun paged(paragraph: ReadingParagraph) {
        val state = EInkPageState(null)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides false) {
                    EInkPage(listOf(paragraph), settings, state, Modifier.size(327.dp, 420.dp).testTag("body"),
                        imageModel = { illustration }, onToggleMenu = {}, onSelect = {}, onPage = {},
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp))
                }
            }
        }
        compose.waitUntil(10_000) { state.ready }
        compose.waitForIdle()
    }

    private fun scrolling(image: Boolean) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalReducedMotion provides false) {
                    val navigation = rememberReaderTapNavigation(true, onToggleMenu = {}, onPage = {})
                    LazyColumn(Modifier.size(327.dp, 420.dp).testTag("body").background(Color.White)
                        .readerTapFeedback(navigation, Color.Black, false).readerTapNavigation(navigation)) {
                        item {
                            if(image) ReaderIllustration(illustration, Color.Black, onToggleMenu = navigation::click)
                            else ReaderTextParagraph(
                                ReadingParagraph(0, listOf(TextPart("沿着林间小路前行。".repeat(400), "gpt"))),
                                settings, Unit, Color.Black, navigation::click, {}, null
                            ) { _, _ -> }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun waitForIllustration() {
        compose.waitUntil(10_000) {
            val pixels = compose.onNodeWithTag("body").captureToImage().toPixelMap()
            abs(pixels[pixels.width / 2, pixels.height / 2].red - 176f / 255f) < .005f
        }
        compose.waitForIdle()
    }

    private fun assertPressDoesNotTintBody(position: Offset) {
        val body = compose.onNodeWithTag("body")
        val before = body.captureToImage().toPixelMap()
        compose.mainClock.autoAdvance = false
        try {
            body.performTouchInput { down(position) }
            // 保持按压，覆盖滚动容器的延迟按压以及涟漪扩散；尚未抬手，不应触发分区反馈。
            compose.mainClock.advanceTimeBy(240)
            val pressed = body.captureToImage().toPixelMap()
            var changed = 0
            for(y in 0 until before.height) for(x in 0 until before.width) {
                val a = before[x, y]
                val b = pressed[x, y]
                if(abs(a.red - b.red) > .005f || abs(a.green - b.green) > .005f || abs(a.blue - b.blue) > .005f) changed++
            }
            assertEquals("正文按下时不应出现整页、段落或插图的默认按压着色", 0, changed)
        } finally {
            body.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun automaticPageBlankSpaceDoesNotTintTheWholePage() {
        paged(ReadingParagraph(0, listOf(TextPart("短段落。", "gpt"))))
        assertPressDoesNotTintBody(Offset(280f, 210f))
    }

    @Test fun automaticPageParagraphDoesNotAddItsOwnPressFeedback() {
        paged(ReadingParagraph(0, listOf(TextPart("沿着林间小路前行。".repeat(400), "gpt"))))
        assertPressDoesNotTintBody(Offset(280f, 210f))
    }

    @Test fun automaticPageIllustrationDoesNotAddItsOwnPressFeedback() {
        paged(ReadingParagraph(0, emptyList(), localImageId = "test-image"))
        waitForIllustration()
        assertPressDoesNotTintBody(Offset(280f, 210f))
    }

    @Test fun scrollingLongParagraphDoesNotTintTheViewport() {
        scrolling(image = false)
        assertPressDoesNotTintBody(Offset(280f, 210f))
    }

    @Test fun scrollingIllustrationDoesNotAddItsOwnPressFeedback() {
        scrolling(image = true)
        waitForIllustration()
        assertPressDoesNotTintBody(Offset(280f, 210f))
    }
}
