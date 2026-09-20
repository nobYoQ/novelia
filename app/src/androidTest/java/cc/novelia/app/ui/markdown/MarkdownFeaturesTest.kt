package cc.novelia.app.ui.markdown

import cc.novelia.app.NoveliaApplication
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.markdown.SpoilerMovementMethod
import cc.novelia.app.ui.markdown.SpoilerPlugin
import cc.novelia.app.ui.markdown.SpoilerSpan
import cc.novelia.app.ui.markdown.SpoilerTextView
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import java.io.File
import org.commonmark.parser.InlineParser
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownFeaturesTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun parserAndRendererFailuresDisplayTheOriginalText() {
        val app = instrumentation.targetContext.applicationContext as NoveliaApplication
        val original = "无法渲染时保留 **完整内容**"
        var root: View? = null
        var failParse by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                root = LocalView.current
                val c = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                val renderer = remember(failParse) {
                    val parsing = failParse
                    Markwon.builder(app).usePlugin(object : AbstractMarkwonPlugin() {
                        override fun configureParser(builder: Parser.Builder) {
                            if(parsing) builder.inlineParserFactory { InlineParser { _, _ -> error("Parser fixture") } }
                        }
                        override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                            if(!parsing) builder.on(org.commonmark.node.Text::class.java) { _, _ -> error("Renderer fixture") }
                        }
                    }).build()
                }
                MarkdownText(c, original, renderer = renderer)
            }
        }
        fun find(view: View): SpoilerTextView? {
            if(view is SpoilerTextView) return view
            if(view is ViewGroup) for(i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        compose.runOnIdle { assertEquals(original, find(root!!.rootView)?.text.toString()); failParse = false }
        compose.runOnIdle { assertEquals(original, find(root!!.rootView)?.text.toString()) }
    }

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
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
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
        compose.waitUntil(5_000) { root?.hasWindowFocus() == true }
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
            // Keep glyph bounds relative to the tested AndroidView. A platform Activity
            // transition can move its screen coordinates after this layout snapshot.
            region = android.graphics.Rect(left.toInt() + 2, top + 2, right.toInt() - 2, bottom - 2)
        }
        compose.onNodeWithTag("spoiler-touch-test").performTouchInput { click(tap) }
        compose.runOnIdle {
            assertTrue("一次真实点击应展开剧透", span.revealed)
            assertTrue("点击后仍应使用同一剧透节点", (view.text as Spanned).getSpanStart(span) >= 0)
            assertFalse(other.revealed)
        }
        compose.waitForIdle()
        // captureToImage uses PixelCopy from the real window, cropped to this node. Check
        // both colors in the same rendered frame instead of accepting frame A then
        // asserting unrelated frames B/C from uiAutomation's full-screen compositor.
        var white = 0
        var black = 0
        var lastFrame: Bitmap? = null
        try {
            compose.waitUntil(5_000) {
                lastFrame?.recycle()
                val frame = compose.onNodeWithTag("spoiler-touch-test").captureToImage().asAndroidBitmap().also { lastFrame = it }
                white = 0; black = 0
                for (y in region.top until region.bottom) for (x in region.left until region.right) {
                    when (frame.getPixel(x, y)) { Color.WHITE -> white++; Color.BLACK -> black++ }
                }
                white > region.width() * region.height() / 3 && black > 20
            }
        } catch(failure: Throwable) {
            val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "spoiler-node-failure-$theme.png")
            lastFrame?.let { bitmap -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            var details = ""
            compose.runOnIdle {
                val buffer = view.text as Spanned
                val paint = android.text.TextPaint(view.paint).also(span::updateDrawState)
                val location = IntArray(2).also(view::getLocationOnScreen)
                details = "view=${view.width}x${view.height}, screen=${location.toList()}, scroll=${view.scrollX},${view.scrollY}, " +
                    "revealed=${span.revealed}, attachedSpan=${buffer.getSpanStart(span)}..${buffer.getSpanEnd(span)}, " +
                    "paint=${paint.color}/${paint.bgColor}, bg=${view.background?.javaClass?.simpleName}, fg=${view.foreground?.javaClass?.simpleName}, " +
                    "windowFocus=${view.hasWindowFocus()}, focused=${view.isFocused}, defaultFocusHighlight=${view.defaultFocusHighlightEnabled}, " +
                    "alpha=${view.alpha}, pressed=${view.isPressed}, selected=${view.isSelected}"
            }
            screenshot("spoiler-window-failure-$theme")
            throw AssertionError("White=$white, black=$black, region=$region, bitmap=${lastFrame?.width}x${lastFrame?.height}; $details; node=${file.name}", failure)
        } finally { lastFrame?.recycle() }
        screenshot("spoiler-real-tap-revealed-$theme")
        assertTrue("展开后应显示白色背景，实际白色像素数 $white", white > region.width() * region.height() / 3)
        assertTrue("展开后应显示黑色文字，实际黑色像素数 $black", black > 20)
        compose.onNodeWithTag("spoiler-touch-test").performTouchInput {
            // Two separate taps: a native double-tap selects text instead.
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            click(tap)
        }
        compose.runOnIdle { assertFalse("再次点击应重新遮黑", span.revealed) }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            val hidden = compose.onNodeWithTag("spoiler-touch-test").captureToImage().asAndroidBitmap()
            black = 0
            for (y in region.top until region.bottom) for (x in region.left until region.right) {
                if (hidden.getPixel(x, y) == Color.BLACK) black++
            }
            hidden.recycle()
            black > region.width() * region.height() * .95
        }
        assertTrue("收起后应完全遮黑", black > region.width() * region.height() * .95)
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
