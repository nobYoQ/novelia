package cc.novelia.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.importing.ImportItem
import cc.novelia.app.files.importing.ImportStatus
import cc.novelia.app.ui.components.documents.ImportResultsPanel
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportResultsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun singleImportReadAndCloseStayDismissedAndNextBatchCanAppear() {
        val imported = ImportItem("book.txt", "是你先喜欢上我的啊.txt", ImportStatus.Success, "已加入书架", "local/import-result-test")
        var items by mutableStateOf(listOf(imported))
        var light by mutableStateOf(false)
        var read: BookRef? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppInteractionMode(false, true) {
                NoveliaTheme(if(light) "light" else "dark") {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if(light) 1.6f else 1f)) {
                        Surface(Modifier.requiredWidth(320.dp)) {
                            Column {
                                ImportResultsPanel(items, false, {}, {}, {}, { read = it }, { items = emptyList() })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("导入完成").assertIsDisplayed()
        screenshot("import-notice-dark")
        compose.runOnIdle { light = true }
        compose.onNodeWithText("阅读").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭导入结果").assertIsDisplayed()
        screenshot("import-notice-light-large-text")
        compose.onNodeWithText("阅读").performClick()
        compose.runOnIdle { assertEquals(BookRef.fromKey(imported.bookKey!!), read) }
        compose.onNodeWithTag("import-status").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("import-status").assertDoesNotExist()
        compose.runOnIdle { items = listOf(imported.copy(status = ImportStatus.Duplicate)) }
        compose.onNodeWithText("文件已在书架中").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭导入结果").performClick()
        compose.onNodeWithTag("import-status").assertDoesNotExist()
    }

    @Test fun readingSuccessfulItemKeepsFailuresAvailableForRetryAndRunningBatchCanPause() {
        val items = listOf(
            ImportItem("good.txt", "已导入的书", ImportStatus.Success, "已加入书架", "local/import-good"),
            ImportItem("bad.epub", "待重试的书", ImportStatus.Failed, "文件损坏"),
        )
        var running by mutableStateOf(false)
        var dismissed = false
        var retried = false
        var paused = false
        var read: BookRef? = null
        compose.setContent {
            AppInteractionMode(false, true) {
                NoveliaTheme("dark") {
                    Column {
                        ImportResultsPanel(items, running, { paused = true; running = false }, { retried = true; running = true }, {},
                            { read = it }, { dismissed = true })
                    }
                }
            }
        }
        compose.onNodeWithTag("import-results").performClick()
        compose.onNodeWithText("开始阅读").performClick()
        compose.runOnIdle { assertEquals(BookRef("local", "import-good"), read); assertFalse(dismissed) }
        compose.onNodeWithTag("import-results").performClick()
        compose.onNodeWithText("仅重试失败项").performClick()
        compose.runOnIdle { assertTrue(retried) }
        compose.onNodeWithContentDescription("收起导入详情").performClick()
        compose.onNodeWithContentDescription("关闭导入结果").assertDoesNotExist()
        compose.onNodeWithContentDescription("暂停导入").performClick()
        compose.runOnIdle { assertTrue(paused); assertFalse(dismissed) }
        compose.onNodeWithContentDescription("关闭导入结果").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onNodeWithTag("import-status").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir("screenshots"), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
