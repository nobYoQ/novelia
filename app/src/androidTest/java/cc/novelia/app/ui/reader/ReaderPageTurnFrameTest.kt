package cc.novelia.app.ui.reader

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import java.io.File
import java.io.FileInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Capture actual rendered pixels around the page commit, using the production reader unchanged. */
class ReaderPageTurnFrameTest {
    @get:Rule val compose = createComposeRule()
    private val state = EInkPageState(null)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val events = mutableListOf<String>()
    private val settings = ReaderSettings(paginationMode = "auto", tapPageTurn = true,
        scrollPageTurn = true, horizontalPageTurn = true, fontSize = 19f, indent = false)
    private val paragraphs = List(120) { index ->
        val marker = ('A'.code + index % 26).toChar().toString().repeat(10)
        ReadingParagraph(index, listOf(TextPart("第 %03d 行  %s".format(index + 1, marker), "gpt")))
    }
    private val output by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "page-turn-frames").apply { mkdirs() }
    }

    private fun fixture(fullScreen: Boolean = false, content: List<ReadingParagraph> = paragraphs) {
        assertTrue("Device animations must be enabled", ValueAnimator.areAnimatorsEnabled())
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(if (fullScreen) 4f else 2f), LocalReducedMotion provides false,
                    LocalEInkMode provides false) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
                        val viewport = if (fullScreen) Modifier.fillMaxSize() else Modifier.size(327.dp, 420.dp)
                        EInkPage(content, settings, state, viewport.testTag("frame-reader"),
                            imageModel = { null }, onToggleMenu = {}, onSelect = {},
                            onPage = { direction ->
                                events += "${SystemClock.elapsedRealtime()},commit,${state.pageIndex},${state.pageIndex + direction}"
                                state.move(direction)
                            }, background = Color.White, foreground = Color.Black,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp))
                    }
                }
            }
        }
        compose.waitUntil(15_000) { state.ready && state.pages.size > 4 }
        compose.waitForIdle()
    }

    private fun screenshot(name: String): Bitmap {
        val bitmap = compose.onNodeWithTag("frame-reader").captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun difference(a: Bitmap, b: Bitmap, inkOnly: Boolean): Double {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val pa = IntArray(a.width * a.height)
        val pb = IntArray(pa.size)
        a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
        var changed = 0
        for (i in pa.indices) {
            val ra = android.graphics.Color.red(pa[i])
            val rb = android.graphics.Color.red(pb[i])
            if (if (inkOnly) (ra < 128) != (rb < 128) else kotlin.math.abs(ra - rb) > 2) changed++
        }
        return changed.toDouble() / pa.size
    }

    private fun gesture(kind: String, forward: Boolean) {
        compose.onNodeWithTag("frame-reader").performTouchInput {
            when (kind) {
                "tap" -> click(Offset(if (forward) width * .85f else width * .15f, centerY))
                "horizontal" -> swipe(Offset(width * (if (forward) .82f else .18f), centerY),
                    Offset(width * (if (forward) .18f else .82f), centerY), durationMillis = 240)
                "vertical" -> swipe(Offset(centerX, height * (if (forward) .82f else .18f)),
                    Offset(centerX, height * (if (forward) .18f else .82f)), durationMillis = 240)
                else -> error(kind)
            }
        }
    }

    @Test fun committedFramesDoNotReturnToThePreviousPage() {
        checkCommittedFrames(paragraphs, "")
    }

    @Test fun changingParagraphNodeCountsDoesNotFlashAfterCommit() {
        val mixed = paragraphs.take(8) + ReadingParagraph(8,
            listOf(TextPart("长段落跨页测试：山间的旅人继续向远处的城镇行进。".repeat(160), "gpt"))) + paragraphs.drop(9)
        checkCommittedFrames(mixed, "mixed-")
    }

    private fun checkCommittedFrames(content: List<ReadingParagraph>, prefix: String) {
        fixture(content = content)
        val report = StringBuilder("gesture,frame,clockMs,page,inkDifferenceToFinal,rawDifferenceToFinal,inkDifferenceToOld\n")
        val failures = mutableListOf<String>()
        try {
            for (kind in listOf("tap", "horizontal", "vertical")) for (forward in listOf(true, false)) {
                val label = "$prefix$kind-${if (forward) "next" else "previous"}"
                val oldIndex = state.pageIndex
                val targetIndex = oldIndex + if (forward) 1 else -1
                val old = screenshot("$label-before")
                val frames = mutableListOf<Triple<Long, Int, Bitmap>>()
                compose.mainClock.autoAdvance = false
                try {
                    gesture(kind, forward)
                    var committedFrames = 0
                    for (frame in 0 until 40) {
                        compose.mainClock.advanceTimeByFrame()
                        val bitmap = screenshot("$label-%02d".format(frame))
                        val page = state.pageIndex
                        frames += Triple(compose.mainClock.currentTime, page, bitmap)
                        if (page == targetIndex) committedFrames++
                        if (committedFrames >= 10) break
                    }
                } finally {
                    compose.mainClock.autoAdvance = true
                }
                compose.waitForIdle()
                val final = screenshot("$label-final")
                assertEquals("$label must commit once", targetIndex, state.pageIndex)
                assertTrue("Fixture must distinguish neighboring pages", difference(old, final, true) > .01)
                frames.forEachIndexed { frame, (time, page, bitmap) ->
                    val ink = difference(bitmap, final, true)
                    val raw = difference(bitmap, final, false)
                    val oldInk = difference(bitmap, old, true)
                    report.append("$label,$frame,$time,$page,$ink,$raw,$oldInk\n")
                    // Ignore the intentional light tap-zone tint, but not changed glyphs or old-page pixels.
                    if (page == targetIndex && ink > .002) failures += "$label frame=$frame committed=$page inkDifference=$ink"
                }
            }
        } finally {
            File(output, "${prefix}controlled-frames.csv").writeText(report.toString(), Charsets.UTF_8)
            File(output, "${prefix}controlled-events.csv").writeText(events.joinToString("\n"), Charsets.UTF_8)
            instrumentation.sendStatus(0, Bundle().apply { putString("frameReport", output.absolutePath) })
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun recordRealTimeTurns() {
        fixture(fullScreen = true)
        val node = compose.onNodeWithTag("frame-reader")
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val movie = File(output, "realtime-paced-turns.mp4")
        val start = SystemClock.elapsedRealtime()
        val recording = instrumentation.uiAutomation.executeShellCommand(
            "screenrecord --size 720x1280 --bit-rate 6000000 --time-limit 35 ${movie.absolutePath}")
        try {
            Thread.sleep(1200)
            screenshot("paced-page-0")
            compose.mainClock.autoAdvance = false
            for (round in 0 until 2) for (kind in listOf("tap", "horizontal", "vertical")) for (forward in listOf(true, false)) {
                val before = state.pageIndex
                events += "${SystemClock.elapsedRealtime()},gesture-$round-$kind-${if (forward) "next" else "previous"},$before"
                if (kind == "tap") {
                    gesture(kind, forward)
                } else {
                    val size = node.fetchSemanticsNode().boundsInRoot.size
                    val horizontal = kind == "horizontal"
                    val startPoint = if (horizontal) Offset(size.width * (if (forward) .82f else .18f), size.height / 2)
                        else Offset(size.width / 2, size.height * (if (forward) .82f else .18f))
                    val endPoint = if (horizontal) Offset(size.width * (if (forward) .18f else .82f), size.height / 2)
                        else Offset(size.width / 2, size.height * (if (forward) .18f else .82f))
                    node.performTouchInput { down(startPoint) }
                    for (step in 1..15) {
                        node.performTouchInput { advanceEventTime(16); moveTo(startPoint + (endPoint - startPoint) * (step / 15f)) }
                        compose.mainClock.advanceTimeByFrame()
                        Thread.sleep(16)
                    }
                    node.performTouchInput { up() }
                }
                repeat(35) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(16) }
                assertEquals(before + if (forward) 1 else -1, state.pageIndex)
                screenshot("paced-page-${state.pageIndex}")
            }
            compose.mainClock.autoAdvance = true
            FileInputStream(recording.fileDescriptor).use { stream -> stream.copyTo(java.io.ByteArrayOutputStream()) }
        } finally {
            compose.mainClock.autoAdvance = true
            recording.close()
            File(output, "paced-events.csv").writeText(events.joinToString("\n"), Charsets.UTF_8)
            File(output, "paced-metadata.txt").writeText(
                "recordingCommandElapsedMs=$start\nboundsInRoot=$bounds\n" +
                    "display=${instrumentation.targetContext.resources.displayMetrics}\n", Charsets.UTF_8)
            instrumentation.sendStatus(0, Bundle().apply { putString("frameReport", output.absolutePath) })
        }
        assertTrue("screenrecord must create a nonempty movie", movie.length() > 1000)
    }
}
