package cc.novelia.app.ui.shelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.updates.ManualShelfCheck
import cc.novelia.app.data.updates.ShelfCheckReport
import cc.novelia.app.data.updates.UpdateWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookUpdatesNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun shelfHasOneUpdateEntryAndAnExistingManualJobDeliversItsOwnResult() {
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("查看书架更新").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val original = app.store.state.value
        val fixture = SavedBook(BookCard(BookRef("local", "check-only"), "本地文件"))
        try {
            compose.runOnIdle { app.store.update { it.copy(books = listOf(fixture), drafts = it.drafts - ShelfCheckReport.KEY) } }
            compose.onNodeWithContentDescription("检查更新").assertDoesNotExist()
            compose.onNodeWithContentDescription("查看书架更新").performClick()
            compose.onNodeWithContentDescription("检查更新").assertIsNotEnabled()
            compose.onNodeWithText("只检查已收藏的在线书籍，本地文件无需检查。").assertIsDisplayed()

            val manager = WorkManager.getInstance(app)
            val queued = OneTimeWorkRequestBuilder<UpdateWorker>().setInitialDelay(2, TimeUnit.SECONDS).build()
            val message = runBlocking { withTimeout(45_000) {
                manager.enqueueUniqueWork(ManualShelfCheck.NAME, ExistingWorkPolicy.KEEP, queued).await()
                ManualShelfCheck.checkNow(app)
            } }
            assertEquals("没有可检查的在线书籍", message)
            val stored = app.store.state.value.drafts[ShelfCheckReport.KEY]
            val output = manager.getWorkInfoById(queued.id).get(5, TimeUnit.SECONDS)?.outputData
            assertEquals(stored, output?.getString(ShelfCheckReport.KEY))
            assertEquals(0, ShelfCheckReport.decode(stored)?.total)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("查看书架更新").performClick()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("最近检查：", substring = true).assertIsDisplayed()
        } finally {
            compose.runOnIdle { app.store.update { it.copy(books = original.books, drafts = original.drafts) } }
        }
    }
}
