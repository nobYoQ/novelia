package cc.novelia.app.ui.downloads

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.downloads.DownloadSheet
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSheetLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun downloadActionRemainsReachableWithLargeFonts() = checkSheet(false)
    @Test fun eInkDownloadActionRemainsReachableWithLargeFonts() = checkSheet(true)
    @Test fun batchDownloadActionRemainsReachableWithLargeFonts() = checkSheet(false, batch = true)
    @Test fun eInkBatchDownloadActionRemainsReachableWithLargeFonts() = checkSheet(true, batch = true)

    @Test fun selectedActionsUseEqualColumnsAndStackTogetherWhenLabelsNeedMoreSpace() {
        val narrow = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if(narrow.value) 1.6f else 1f)) {
                NoveliaTheme("light") {
                    Box(Modifier.width(if(narrow.value) 240.dp else 360.dp)) {
                        DownloadBatchActions(7, 7, true, false, {}, {}, {}, {})
                    }
                }
            }
        }
        fun bounds() = listOf("import", "export", "share", "delete").map {
            compose.onNodeWithTag("download-batch-$it").getUnclippedBoundsInRoot()
        }
        val (a, b, c, d) = bounds()
        assertEquals(a.top, b.top)
        assertEquals(c.top, d.top)
        assertTrue(b.left > a.right && c.top > a.top)
        assertEquals(a.left, c.left)
        assertEquals(b.right, d.right)
        assertEquals((a.right - a.left).value, (b.right - b.left).value, 1f)
        compose.runOnIdle { narrow.value = true }
        bounds().zipWithNext().forEach { (first, next) ->
            assertEquals(first.left, next.left)
            assertEquals(first.right, next.right)
            assertTrue(first.bottom <= next.top)
        }
    }

    private fun checkSheet(eInk: Boolean, batch: Boolean = false) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                AppInteractionMode(eInk, true) {
                    NoveliaTheme("light") {
                        val controller = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                        DownloadSheet(controller, BookCard(BookRef(if(batch) "wenku" else "syosetu", "layout-test"), "大字体下载面板布局测试"),
                            if(batch) listOf("第1卷.epub", "第2卷.epub") else emptyList()) {}
                    }
                }
            }
        }
        // 只测试滚动，不得创建下载任务或访问原站。
        compose.onNodeWithText("开始下载").performScrollTo().assertIsDisplayed()
    }
}
