package cc.novelia.app.ui.book

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.JapaneseVolume
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class WenkuBatchDownloadTest {
    @get:Rule val compose = createComposeRule()
    private val ref = BookRef("wenku", "batch-ui-test")
    private val first = JapaneseVolume("第1卷.epub", total = 10, sakura = 10)
    private val second = JapaneseVolume("第2卷.txt", total = 12, gpt = 12)
    private val incomplete = JapaneseVolume("第3卷.epub", total = 10, sakura = 9, gpt = 9)
    private val empty = JapaneseVolume("第4卷.epub")

    @Test fun selectionExcludesUnavailableVolumesAndOpensOneSharedDownloadSheet() {
        val detail = mutableStateOf(WenkuDetail(title = "多卷测试", volumeJp = listOf(first, second, incomplete, empty)))
        val restore = setup(detail)
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithTag("wenku-batch-download").assertIsNotEnabled()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithTag("wenku-volume-${first.volumeId}").assertIsOn()
        compose.onNodeWithTag("wenku-volume-${second.volumeId}").assertIsOn()
        compose.onNodeWithTag("wenku-volume-${incomplete.volumeId}").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("wenku-volume-${empty.volumeId}").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("已选 2 卷").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("已选 2 卷").assertIsDisplayed()
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithTag("wenku-batch-download").assertIsNotEnabled()
        compose.onNodeWithTag("wenku-volume-${first.volumeId}").performClick()
        compose.onNodeWithTag("wenku-volume-${second.volumeId}").performClick()
        compose.onNodeWithTag("wenku-batch-download").performClick()
        compose.onNodeWithText("批量下载分卷").assertIsDisplayed()
        compose.onNodeWithText("开始下载").performScrollTo().assertIsDisplayed()
        // 只验证选择和表单，不创建下载任务或请求原站。
        compose.onNodeWithText("关闭面板").performClick()
        compose.onNodeWithText("已选 2 卷").assertIsDisplayed()
    }

    @Test fun refreshedVolumesRemoveSelectionsThatAreNoLongerDownloadable() {
        val detail = mutableStateOf(WenkuDetail(title = "多卷测试", volumeJp = listOf(first, second)))
        setup(detail)
        compose.onNodeWithText("多选").performClick()
        compose.onNodeWithText("全选").performClick()
        compose.runOnIdle { detail.value = detail.value.copy(volumeJp = listOf(second.copy(total = 13))) }
        compose.onNodeWithText("已选 0 卷").assertIsDisplayed()
        compose.onNodeWithTag("wenku-batch-download").assertIsNotEnabled()
        compose.onNodeWithTag("wenku-volume-${second.volumeId}").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("已选 0 卷").assertDoesNotExist()
    }

    private fun setup(detail: State<WenkuDetail>): StateRestorationTester {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        return StateRestorationTester(compose).also { restore ->
            restore.setContent {
                AppInteractionMode(false, true) {
                    NoveliaTheme("light") {
                        val nav = rememberNavController()
                        val scope = rememberCoroutineScope()
                        val c = remember { AppController(app, nav, scope, SnackbarHostState()) }
                        WenkuVolumesPanel(c, detail.value.card(ref), detail.value, false, onUpload = {}, onRefresh = {})
                    }
                }
            }
        }
    }
}
