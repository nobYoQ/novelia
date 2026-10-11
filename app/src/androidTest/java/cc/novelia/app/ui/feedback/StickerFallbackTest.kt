package cc.novelia.app.ui.feedback

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.R
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.book.BookCover
import cc.novelia.app.ui.theme.LocalReducedMotion
import java.io.File
import java.net.UnknownHostException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StickerFallbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eventMotionPlaysOnceThenRestsAndHonorsReducedMotion() {
        assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        var trigger by mutableIntStateOf(0)
        var reduced by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                StickerAccent(MidoriSticker.Celebrate, trigger, Modifier.size(96.dp))
            }
        } }
        fun pixels(): IntArray = compose.onRoot().captureToImage().let { image ->
            IntArray(image.width * image.height).also { image.readPixels(it) }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { trigger++ }
        compose.mainClock.advanceTimeBy(100)
        val early = pixels()
        compose.mainClock.advanceTimeBy(250)
        assertFalse("The celebration should move", early.contentEquals(pixels()))
        compose.mainClock.advanceTimeBy(1000)
        val settled = pixels()
        compose.mainClock.advanceTimeBy(250)
        assertArrayEquals("The event should stop animating", settled, pixels())
        compose.runOnIdle { reduced = true; trigger++ }
        compose.mainClock.advanceTimeBy(100)
        val reducedFrame = pixels()
        compose.mainClock.advanceTimeBy(250)
        assertArrayEquals("Reduced motion must stay still", reducedFrame, pixels())
        compose.mainClock.autoAdvance = true
    }

    @Test fun missingAndBrokenCoversFallBackWhileExistingCoversLoadNormally() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var cover by mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                Column { BookCover(BookCard(BookRef("local", "fallback"), "午后阅读", cover = cover)) }
            }
        } }
        compose.onNodeWithContentDescription("午后阅读 默认封面").assertIsDisplayed()
        compose.runOnIdle { cover = "android.resource://${context.packageName}/${R.drawable.midori_reading}" }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("午后阅读 封面").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("午后阅读 默认封面").assertDoesNotExist()
        compose.runOnIdle { cover = File(context.cacheDir, "missing-cover-${System.nanoTime()}").absolutePath }
        compose.onNodeWithContentDescription("午后阅读 默认封面").assertIsDisplayed()
        compose.runOnIdle { cover = " " }
        compose.onNodeWithContentDescription("午后阅读 默认封面").assertIsDisplayed()
    }

    @Test fun networkStickerPreservesTheReasonAndWorkingRetry() {
        var attempts = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                AsyncContent("network-sticker", load = { if(++attempts == 1) throw UnknownHostException() else "内容已恢复" }) { value, _ -> Text(value) }
            }
        } }
        compose.onNodeWithText("网络不可用，请检查连接。已缓存的章节仍可在书架中阅读。").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("sticker-screenshots"), "v2-network-error.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("内容已恢复").assertIsDisplayed()
    }
}
