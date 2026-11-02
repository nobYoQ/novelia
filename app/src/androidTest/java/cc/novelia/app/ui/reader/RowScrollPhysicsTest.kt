package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** 用连续滚动实际使用的 LazyColumn 对照相同触摸轨迹，避免仅测试某个减速公式。 */
class RowScrollPhysicsTest {
    @get:Rule val compose = createComposeRule()
    private val row = RowPagingState(null)
    private val continuous = LazyListState(100)
    private val text = (1..2000).joinToString(" ") { "第${it}行，两种阅读模式应具有一致的纵向滚动手感。" }

    private fun show(density: Float) {
        compose.setContent { NoveliaTheme("light") {
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                Column {
                    RowPagedReader(listOf(ReadingParagraph(0, listOf(TextPart(text, "local")))),
                        ReaderSettings(paginationMode = "row", horizontalPageTurn = true,
                            fontSize = 22f, lineHeight = 1.7f, indent = false), row,
                        Modifier.requiredSize(300.dp, 337.dp).testTag("row"),
                        imageModel = { null }, onToggleMenu = {}, onSelect = {}, onPage = row::move)
                    LazyColumn(state = continuous, overscrollEffect = null,
                        modifier = Modifier.requiredSize(300.dp, 337.dp).testTag("continuous")) {
                        items(1000) { Spacer(Modifier.fillMaxWidth().height(100.dp)) }
                    }
                }
            }
        } }
        compose.waitUntil(10_000) { row.ready }
        compose.runOnIdle { row.seekPage(30) }
    }

    private fun continuousTop(density: Float) = continuous.firstVisibleItemIndex * 100 * density + continuous.firstVisibleItemScrollOffset

    private fun compareSwipe(duration: Long, density: Float = 1f, downwards: Boolean = false) {
        show(density)
        val from = Offset(150f, if(downwards) 50f else 290f) * density
        val to = Offset(150f, if(downwards) 290f else 50f) * density
        val rowStart = row.viewportTop
        compose.onNodeWithTag("row").performTouchInput { swipe(from, to, duration) }
        compose.waitForIdle()
        val rowTravel = row.viewportTop - rowStart
        val continuousStart = continuousTop(density)
        compose.onNodeWithTag("continuous").performTouchInput { swipe(from, to, duration) }
        compose.waitForIdle()
        val continuousTravel = continuousTop(density) - continuousStart
        val rowHeight = row.layout!!.lines[row.row].height
        // 行带模式最后会吸附最近的完整行；除此之外，拖动和惯性距离应接近列表。
        val tolerance = rowHeight / 2f + 3 * density
        assertTrue("两种模式应沿同一方向移动", rowTravel * continuousTravel > 0)
        assertTrue("同一手势距离不一致: duration=$duration density=$density row=$rowTravel continuous=$continuousTravel tolerance=$tolerance",
            abs(rowTravel - continuousTravel) <= tolerance)
        assertFalse(row.scrolling)
    }

    @Test fun fastUpwardSwipeMatchesContinuousScrolling() = compareSwipe(80)
    @Test fun fastDownwardSwipeMatchesContinuousScrolling() = compareSwipe(80, downwards = true)
    @Test fun mediumSwipeMatchesContinuousScrolling() = compareSwipe(300)
    @Test fun gentleSwipeMatchesContinuousScrolling() = compareSwipe(2000)
    @Test fun highDensitySwipeMatchesContinuousScrolling() = compareSwipe(80, density = 2.5f)

    @Test fun draggingMovesOnePixelPerFingerPixelBeforeRelease() {
        show(1f)
        val page = compose.onNodeWithTag("row")
        page.performTouchInput { down(Offset(150f, 280f)); moveBy(Offset(0f, -60f), 100) }
        val from = row.viewportTop
        page.performTouchInput { moveBy(Offset(0f, -90f), 100) }
        assertEquals(from + 90f, row.viewportTop, .01f)
        page.performTouchInput { advanceEventTime(250); up() }
        compose.waitForIdle()
        assertFalse(row.scrolling)
    }
}
