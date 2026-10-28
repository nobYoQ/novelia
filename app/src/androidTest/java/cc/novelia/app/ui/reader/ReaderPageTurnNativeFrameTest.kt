package cc.novelia.app.ui.reader

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test

/** 不安装 Compose 测试时钟；使用系统 Choreographer、原生触摸与 screenrecord。 */
class ReaderPageTurnNativeFrameTest {
    @Test fun recordNativeClockTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val state = EInkPageState(null)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "page-turn-native-frames").apply { mkdirs() }
        val settings = ReaderSettings(paginationMode = "auto", tapPageTurn = true,
            scrollPageTurn = true, horizontalPageTurn = true, fontSize = 19f, indent = false)
        val paragraphs = List(120) { index -> ReadingParagraph(index, listOf(TextPart(
            "第 %03d 行  %s".format(index + 1, ('A'.code + index % 26).toChar().toString().repeat(10)), "gpt"))) }
        assertTrue(ValueAnimator.areAnimatorsEnabled())

        fun waitForState(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var done = false
            while (!done && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { done = predicate() }
                if (!done) Thread.sleep(10)
            }
            assertTrue("Native reader did not reach the expected state", done)
        }
        fun screenshot(page: Int) {
            val bitmap = automation.takeScreenshot()
            assertNotNull("Screenshot unavailable", bitmap)
            File(output, "page-$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun inject(action: Int, downTime: Long, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalReducedMotion provides false, LocalEInkMode provides false) {
                            EInkPage(paragraphs, settings, state, Modifier.fillMaxSize(), imageModel = { null },
                                onToggleMenu = {}, onSelect = {}, onPage = { direction ->
                                    val old = state.pageIndex
                                    state.move(direction)
                                    events += "${SystemClock.elapsedRealtime()},commit,$old,${state.pageIndex}"
                                }, background = Color.White, foreground = Color.Black,
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp))
                        }
                    }
                }
            }
            waitForState { state.ready && state.pages.size > 4 }
            Thread.sleep(500)
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            val movie = File(output, "native-clock-turns.mp4")
            val start = SystemClock.elapsedRealtime()
            val recording = automation.executeShellCommand(
                "screenrecord --size 720x1280 --bit-rate 6000000 --time-limit 25 ${movie.absolutePath}")
            try {
                Thread.sleep(1000)
                screenshot(0)
                for (round in 0 until 2) for (kind in listOf("tap", "horizontal", "vertical")) for (forward in listOf(true, false)) {
                    val before = if (forward) 0 else 1
                    events += "${SystemClock.elapsedRealtime()},gesture-$round-$kind-${if (forward) "next" else "previous"},$before"
                    val horizontal = kind != "vertical"
                    val x0 = if (horizontal) width * (if (forward) .85f else .15f) else width / 2
                    val y0 = if (horizontal) height / 2 else height * (if (forward) .82f else .18f)
                    val x1 = if (horizontal) width - x0 else x0
                    val y1 = if (horizontal) y0 else height - y0
                    val downTime = SystemClock.uptimeMillis()
                    inject(MotionEvent.ACTION_DOWN, downTime, x0, y0)
                    if (kind == "tap") {
                        Thread.sleep(32)
                        inject(MotionEvent.ACTION_UP, downTime, x0, y0)
                    } else {
                        for (step in 1..15) {
                            Thread.sleep(16)
                            inject(MotionEvent.ACTION_MOVE, downTime,
                                x0 + (x1 - x0) * step / 15, y0 + (y1 - y0) * step / 15)
                        }
                        inject(MotionEvent.ACTION_UP, downTime, x1, y1)
                    }
                    waitForState { state.pageIndex == before + if (forward) 1 else -1 }
                    Thread.sleep(500)
                    screenshot(if (forward) 1 else 0)
                }
                FileInputStream(recording.fileDescriptor).use { it.copyTo(java.io.ByteArrayOutputStream()) }
            } finally {
                recording.close()
                File(output, "events.csv").writeText(events.joinToString("\n"), Charsets.UTF_8)
                File(output, "metadata.txt").writeText("recordingCommandElapsedMs=$start\ndisplay=$metrics\n", Charsets.UTF_8)
                instrumentation.sendStatus(0, Bundle().apply { putString("frameReport", output.absolutePath) })
            }
            assertTrue(movie.length() > 1000)
        }
    }
}
