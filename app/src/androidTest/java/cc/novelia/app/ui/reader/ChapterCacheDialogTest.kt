package cc.novelia.app.ui.reader

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.chapters.ChapterCacheQueue
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test

class ChapterCacheDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closingDialogKeepsFirstBatchAliveWhileCachingAnotherBook() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = BookRef("syosetu", "first")
        val second = BookRef("syosetu", "second")
        val queue = ChapterCacheQueue(scope) { request, progress ->
            progress(1, 2)
            if (request.key.book == first) awaitCancellation()
            progress(2, 2)
        }
        var showing by mutableStateOf<BookRef?>(first)
        val chapters = listOf(TocItem(chapterId = "a"), TocItem(chapterId = "b"))
        try {
            compose.setContent { NoveliaTheme("light") {
                val c = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                showing?.let { ChapterCacheDialog(c, it, chapters, null, queue) { showing = null } }
            } }
            compose.onNodeWithText("开始缓存").performClick()
            compose.onNodeWithText("后台继续").performClick()
            compose.runOnIdle { showing = second }
            compose.onNodeWithText("开始缓存").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("缓存完成，可在目录查看当前离线范围。").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭").performClick()
            compose.runOnIdle { showing = first }
            compose.onNodeWithText("正在缓存 1 / 2 章").assertIsDisplayed()
            compose.onNodeWithText("取消缓存").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("已取消，已完成的缓存会保留。").fetchSemanticsNodes().isNotEmpty() }
        } finally { scope.cancel() }
    }
}
