package cc.novelia.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.ui.ReaderIllustration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IllustrationViewerTest {
    @get:Rule val compose = createComposeRule()
    private fun viewport() = compose.onNodeWithTag("illustration-viewport")
    private fun scale(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    private fun waitForImage() = compose.waitUntil(10_000) {
        compose.onAllNodesWithContentDescription("放大插图").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
    }

    @Test fun longPressPinchPanDoubleTapAndCloseKeepSingleTapSeparate() {
        var taps = 0
        compose.setContent { MaterialTheme { ReaderIllustration(R.drawable.midori_reading, Color.Black) { taps++ } } }
        compose.onNodeWithContentDescription("小说插图").performTouchInput { longClick() }
        waitForImage()
        compose.runOnIdle { assertEquals(0, taps) }
        viewport().assert(scale("100%"))
        compose.onNodeWithContentDescription("缩小插图").assertIsNotEnabled()
        compose.onNodeWithContentDescription("放大插图").performClick()
        viewport().assert(scale("150%"))
        viewport().performTouchInput { swipe(center, center + Offset(80f, 50f)) }
        viewport().assert(scale("150%"))
        viewport().performTouchInput { doubleClick(center) }
        viewport().assert(scale("100%"))
        viewport().performTouchInput { doubleClick(center) }
        viewport().assert(scale("250%"))
        compose.onNodeWithContentDescription("还原插图").performClick()
        viewport().performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(140f, 0f), center + Offset(40f, 0f), center + Offset(140f, 0f))
        }
        viewport().assert(!scale("100%"))
        repeat(5) { compose.onNodeWithContentDescription("放大插图").performClick() }
        viewport().assert(scale("500%"))
        compose.onNodeWithContentDescription("放大插图").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭插图").performClick()
        viewport().assertDoesNotExist()
        compose.onNodeWithContentDescription("小说插图").performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test fun unavailableImageHasRetryAndAccessibleLongPress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("viewer-retry-", ".webp", context.cacheDir)
        try {
            compose.setContent { MaterialTheme { ReaderIllustration(file, Color.Black) {} } }
            compose.onNodeWithContentDescription("小说插图").performSemanticsAction(SemanticsActions.OnLongClick)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("放大插图").assertIsNotEnabled()
            context.resources.openRawResource(R.drawable.midori_reading).use { input -> file.outputStream().use(input::copyTo) }
            compose.onNodeWithText("重试").performClick()
            waitForImage()
            viewport().assert(scale("100%"))
            compose.onNodeWithContentDescription("关闭插图").performClick()
        } finally { file.delete() }
    }
}
