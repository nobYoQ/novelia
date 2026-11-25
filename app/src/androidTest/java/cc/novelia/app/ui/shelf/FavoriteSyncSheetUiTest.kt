package cc.novelia.app.ui.shelf

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.library.*
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FavoriteSyncSheetUiTest {
    @get:Rule val compose = createComposeRule()
    private val book = BookCard(BookRef("wenku", "1"), "待移动的书")
    private val change = FavoriteSyncChange(book, "旧夹", FavoriteDestination("新夹", create = true))

    @Test fun checkingAndSwitchingPriorityDoNotWriteAndConfirmationUsesTheVisiblePlan() {
        val checks = mutableListOf<FavoriteSyncPriority>()
        val applied = mutableListOf<FavoriteSyncPlan>()
        val visible = mutableStateOf(true)
        compose.setContent { NoveliaTheme("dark") {
            if(visible.value) FavoriteSyncSheet("读者", { priority, _ ->
                checks += priority; FavoriteSyncPlan(priority, listOf(change))
            }, { plan, _, progress -> applied += plan; progress(plan.changes.size, 0) },
                onDismiss = { visible.value = false }, onFinished = { visible.value = false })
        } }
        compose.onNodeWithText("先预览，再同步").assertDoesNotExist()
        compose.onNodeWithText("确认同步").assertDoesNotExist()
        compose.onNodeWithText("检查差异").performClick()
        compose.onNodeWithText(book.title).assertIsDisplayed()
        compose.onNodeWithText("旧夹 → 新夹").assertIsDisplayed()
        compose.onNodeWithText("移动 · 文库小说 · 新建收藏夹").assertIsDisplayed()
        saveTestScreenshot("favorite-sync-preview")
        compose.onNodeWithText("更换基准").performClick()
        compose.onNodeWithText("确认同步").assertDoesNotExist()
        compose.onNodeWithText("优先云端").performClick()
        compose.onNodeWithText("检查差异").performClick()
        compose.onNodeWithText("云端 → 本地 · 读者").assertIsDisplayed()
        compose.runOnIdle { assertEquals(FavoriteSyncPriority.entries.toList(), checks); assertTrue(applied.isEmpty()) }
        compose.onNodeWithText("确认同步").performClick()
        compose.runOnIdle { assertEquals(listOf(FavoriteSyncPlan(FavoriteSyncPriority.Cloud, listOf(change))), applied) }
    }

    @Test fun emptyPreviewCanCloseWithoutAnApplyAction() {
        val visible = mutableStateOf(true)
        compose.setContent { NoveliaTheme("dark") {
            if(visible.value) FavoriteSyncSheet("读者", { priority, _ -> FavoriteSyncPlan(priority, emptyList()) },
                { _, _, _ -> fail("无差异时不能执行同步") }, onDismiss = { visible.value = false }, onFinished = { fail() })
        } }
        compose.onNodeWithText("检查差异").performClick()
        compose.onNodeWithText("无需同步", substring = true).assertIsDisplayed()
        compose.onNodeWithText("确认同步").assertDoesNotExist()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("同步预览").assertDoesNotExist()
    }

    @Test fun cancellingANonemptyPreviewNeverAppliesIt() {
        val visible = mutableStateOf(true)
        compose.setContent { NoveliaTheme("dark") {
            if(visible.value) FavoriteSyncSheet("读者", { priority, _ -> FavoriteSyncPlan(priority, listOf(change)) },
                { _, _, _ -> fail("取消不能写入收藏") }, onDismiss = { visible.value = false }, onFinished = { fail() })
        } }
        compose.onNodeWithText("检查差异").performClick()
        compose.onNodeWithContentDescription("关闭收藏同步").performClick()
        compose.onNodeWithText("同步预览").assertDoesNotExist()
    }

    @Test fun longPreviewAllowsReviewingTheLastBookWithTheConfirmButtonInPlace() {
        val changes = (1..60).map { index ->
            change.copy(book = BookCard(BookRef("wenku", index.toString()), "待同步书目 $index"))
        }
        compose.setContent { NoveliaTheme("light") {
            FavoriteSyncSheet("读者", { priority, _ -> FavoriteSyncPlan(priority, changes) },
                { _, _, _ -> fail("查看清单不能写入收藏") }, {}, {})
        } }
        compose.onNodeWithText("检查差异").performClick()
        val confirm = compose.onNodeWithText("确认同步").assertIsDisplayed()
        val position = confirm.fetchSemanticsNode().boundsInWindow
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(changes.size)
        compose.onNodeWithText(changes.last().book.title).assertIsDisplayed()
        assertEquals(position, confirm.assertIsDisplayed().fetchSemanticsNode().boundsInWindow)
    }

    @Test fun checkShowsActualPageAndFolderProgressWithoutEnablingWrites() {
        val folders = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        compose.setContent { NoveliaTheme("light") {
            FavoriteSyncSheet("读者", { priority, status ->
                status(FavoriteSyncProgress(FavoriteSyncPhase.ReadWeb, 2, 5, 40))
                folders.await()
                status(FavoriteSyncProgress(FavoriteSyncPhase.ResolveFolders, 30, 40))
                finish.await()
                FavoriteSyncPlan(priority, listOf(change))
            }, { _, _, _ -> fail("检查不能执行写入") }, {}, {})
        } }
        saveTestScreenshot("favorite-sync-setup")
        compose.onNodeWithText("检查差异").performClick()
        compose.onNodeWithText("已读 2 / 5 页 · 40 本").assertIsDisplayed()
        compose.onNodeWithTag("favorite-sync-progress").assertRangeInfoEquals(ProgressBarRangeInfo(.4f, 0f..1f))
        saveTestScreenshot("favorite-sync-progress")
        compose.onNodeWithText("确认同步").assertDoesNotExist()
        compose.runOnIdle { folders.complete(Unit) }
        compose.onNodeWithText("已确认 30 / 40 本的归属").assertIsDisplayed()
        compose.onNodeWithTag("favorite-sync-progress").assertRangeInfoEquals(ProgressBarRangeInfo(.75f, 0f..1f))
        compose.runOnIdle { finish.complete(Unit) }
        compose.onNodeWithText("确认同步").assertIsDisplayed()
    }
}
