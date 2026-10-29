package cc.novelia.app.ui.notes

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.NoteSelection
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NoteExportUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pureHighlightsAreVisibleAndTheExportUsesTheSearchScope() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        try {
            app.store.update { it.copy(notes = listOf(
                Note("export-one", "local/export", "one", 0, "纯划线摘录", "", bookTitle = "测试书籍", bookmarked = false, selection = NoteSelection("original", start = 0, end = 5)),
                Note("export-two", "local/export", "two", 0, "另一个摘录", "", bookTitle = "测试书籍"))) }
            compose.setContent { NoveliaTheme("light") {
                NotesScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }))
            } }
            compose.onNodeWithText("纯划线摘录").assertIsDisplayed()
            compose.onNodeWithTag("notes-search").performTextInput("纯划线")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("1 条").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("导出筛选结果").performClick()
            compose.onNodeWithText("导出当前书籍与搜索筛选下的 1 条记录，包含摘录、批注和章节位置。").assertIsDisplayed()
            compose.onNodeWithText("Markdown（.md）").assertIsDisplayed()
            compose.onNodeWithText("纯文本（.txt）").assertIsDisplayed()
        } finally { app.store.update { before } }
    }
}
