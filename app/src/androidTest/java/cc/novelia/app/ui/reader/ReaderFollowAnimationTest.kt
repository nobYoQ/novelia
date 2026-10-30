package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.reader.ParagraphScrollLayout
import cc.novelia.app.reader.ReadingAnchorLine
import cc.novelia.app.reader.ReadingTextMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderFollowAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun highlightCrossfadesAndReducedMotionImmediatelyUsesCurrentSentence() {
        val first = ReadingTextMatch(0, 0, 0, 8)
        val second = ReadingTextMatch(1, 0, 0, 12)
        var match by mutableStateOf(first)
        var animate by mutableStateOf(true)
        var layers = emptyList<ReaderHighlightLayer>()
        compose.setContent { layers = rememberReaderHighlightLayers(match, animate) }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { match = second }
            compose.mainClock.advanceTimeBy(80)
            compose.runOnIdle {
                assertTrue(layers.any { it.match == first && it.alpha > 0f && it.alpha < 1f })
                assertTrue(layers.any { it.match == second && it.alpha > 0f && it.alpha < 1f })
            }
            compose.runOnIdle { animate = false }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle { assertEquals(listOf(ReaderHighlightLayer(second, 1f)), layers) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun followingMeasuredLongParagraphNeverJumpsBackToItsStart() {
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            LazyColumn(Modifier.height(300.dp), state = list) {
                item { Text("长段落", Modifier.height(2400.dp)) }
                item { Text("下一段", Modifier.height(600.dp)) }
            }
        }
        val layout = ParagraphScrollLayout(mapOf(0 to listOf(
            ReadingAnchorLine(0, 10, 0), ReadingAnchorLine(10, 20, 600), ReadingAnchorLine(20, 30, 1200))))
        compose.runOnIdle { scope.launch { list.scrollToItem(0, 600) } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { scope.launch { list.revealReadingLine(0, 20, 1, true, 0) { layout } } }
            compose.mainClock.advanceTimeBy(80)
            val middle = list.firstVisibleItemScrollOffset
            assertTrue("不能先闪回段首", middle > 600)
            compose.mainClock.advanceTimeBy(1500)
            assertTrue("跟随应经过中间滚动位置", list.firstVisibleItemScrollOffset > middle)
            compose.runOnIdle { scope.launch { list.revealReadingLine(0, 10, 1, false, 0) { layout } } }
            compose.mainClock.advanceTimeByFrame()
            assertEquals("减少动效应精确定位", 600, list.firstVisibleItemScrollOffset)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
