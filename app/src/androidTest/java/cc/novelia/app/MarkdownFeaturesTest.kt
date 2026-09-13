package cc.novelia.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.ui.*
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MarkdownFeaturesTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun realTapRevealsWhiteBackgroundAndBlackTextInSelectableMarkdown() = checkRealSpoilerTap("dark")
    @Test fun realTapRevealsWhiteBackgroundAndBlackTextInLightTheme() = checkRealSpoilerTap("light")

    private fun checkRealSpoilerTap(theme: String) {
        val app = instrumentation.targetContext.applicationContext as NoveliaApplication
        var root: View? = null
        compose.setContent {
            NoveliaTheme(theme) {
                root = LocalView.current
                val c = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        MarkdownText(c, "正文 !!**秘密结局**!! 后文 !!另一处!!", Modifier.testTag("spoiler-touch-test"))
                    }
                }
            }
        }
        fun findTextView(view: View): SpoilerTextView? {
            if (view is SpoilerTextView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findTextView(view.getChildAt(i))?.let { return it }
            return null
        }
        lateinit var view: SpoilerTextView
        lateinit var span: SpoilerSpan
        lateinit var other: SpoilerSpan
        var tap = Offset.Zero
        var region = android.graphics.Rect()
        compose.runOnIdle {
            view = findTextView(root!!.rootView)!!
            assertTrue(view.isTextSelectable)
            val text = view.text as Spanned
            val spans = text.getSpans(0, text.length, SpoilerSpan::class.java).sortedBy { text.getSpanStart(it) }
            span = spans[0]
            other = spans[1]
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val line = view.layout.getLineForOffset(start)
            val left = view.layout.getPrimaryHorizontal(start) + view.totalPaddingLeft
            val right = view.layout.getPrimaryHorizontal(end) + view.totalPaddingLeft
            val top = view.layout.getLineTop(line) + view.totalPaddingTop
            val bottom = view.layout.getLineBottom(line) + view.totalPaddingTop
            tap = Offset((left + right) / 2, (top + bottom) / 2f)
            val location = IntArray(2).also(view::getLocationOnScreen)
            region = android.graphics.Rect(location[0] + left.toInt() + 2, location[1] + top + 2,
                location[0] + right.toInt() - 2, location[1] + bottom - 2)
        }
        compose.onNodeWithTag("spoiler-touch-test").performTouchInput { click(tap) }
        compose.runOnIdle { assertTrue("一次真实点击应展开剧透", span.revealed); assertFalse(other.revealed) }
        compose.waitForIdle()
        // Native activity enter animations can continue after Compose becomes idle.
        // Wait for the actual rendered pixels instead of sampling a transition frame.
        compose.waitUntil(5_000) {
            val frame = instrumentation.uiAutomation.takeScreenshot()
            try {
                var white = 0
                for (y in region.top until region.bottom) for (x in region.left until region.right) if (frame.getPixel(x, y) == Color.WHITE) white++
                white > region.width() * region.height() / 3
            } finally { frame.recycle() }
        }
        screenshot("spoiler-real-tap-revealed-$theme")
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            var white = 0
            var black = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                when (bitmap.getPixel(x, y)) { Color.WHITE -> white++; Color.BLACK -> black++ }
            }
            assertTrue("展开后应显示白色背景，实际白色像素数 $white", white > region.width() * region.height() / 3)
            assertTrue("展开后应显示黑色文字，实际黑色像素数 $black", black > 20)
        } finally { bitmap.recycle() }
        compose.onNodeWithTag("spoiler-touch-test").performTouchInput {
            // Two separate taps: a native double-tap selects text instead.
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            click(tap)
        }
        compose.runOnIdle { assertFalse("再次点击应重新遮黑", span.revealed) }
        compose.waitForIdle()
        val hidden = instrumentation.uiAutomation.takeScreenshot()
        try {
            var black = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                if (hidden.getPixel(x, y) == Color.BLACK) black++
            }
            assertTrue("收起后应完全遮黑", black > region.width() * region.height() * .95)
        } finally { hidden.recycle() }
    }

    private fun screenshot(name: String) {
        val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun spoilersMaskFormattedLinksAndRevealIndependently() {
        instrumentation.runOnMainSync {
            var links = 0
            val context = instrumentation.targetContext
            val markwon = Markwon.builder(context).usePlugin(SpoilerPlugin()).usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, _ -> links++ }
                }
            }).build()
            val view = SpoilerTextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(850, ViewGroup.LayoutParams.WRAP_CONTENT)
                textSize = 20f; setTextColor(Color.RED); setBackgroundColor(Color.WHITE)
            }
            markwon.setMarkdown(view, "前文 !!**秘密结局**和[链接](https://example.com)!! 后文 !!另一处!!")
            view.movementMethod = SpoilerMovementMethod()
            view.measure(View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val text = view.text as Spanned
            val spoilers = text.getSpans(0, text.length, SpoilerSpan::class.java).sortedBy { text.getSpanStart(it) }
            assertEquals(2, spoilers.size)
            assertFalse(view.createAccessibilityNodeInfo().text.toString().contains("秘密结局"))
            val first = spoilers.first()
            val start = text.getSpanStart(first)
            val x = view.layout.getPrimaryHorizontal(start) + 5f
            val y = view.layout.getLineBottom(view.layout.getLineForOffset(start)) - 5f
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertEquals(Color.BLACK, bitmap.getPixel(x.toInt(), y.toInt()))
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 1, action, x, y, 0)
                view.movementMethod.onTouchEvent(view, view.text as android.text.Spannable, event)
                event.recycle()
            }
            assertTrue(first.revealed)
            assertFalse(spoilers.last().revealed)
            assertEquals(0, links)
            assertTrue(view.createAccessibilityNodeInfo().text.toString().contains("秘密结局"))
            first.onClick(view)
            assertFalse(first.revealed)
            bitmap.recycle()
        }
    }

}
