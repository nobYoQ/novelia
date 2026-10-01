package cc.novelia.app.ui.downloads

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
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
import org.junit.Test

class DownloadSheetLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun downloadActionRemainsReachableWithLargeFonts() = checkSheet(false)
    @Test fun eInkDownloadActionRemainsReachableWithLargeFonts() = checkSheet(true)

    private fun checkSheet(eInk: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                AppInteractionMode(eInk, true) {
                    NoveliaTheme("light") {
                        val controller = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                        DownloadSheet(controller, BookCard(BookRef("syosetu", "layout-test"), "大字体下载面板布局测试"), null) {}
                    }
                }
            }
        }
        // 只测试滚动，不得创建下载任务或访问原站。
        compose.onNodeWithText("开始下载").performScrollTo().assertIsDisplayed()
    }
}
