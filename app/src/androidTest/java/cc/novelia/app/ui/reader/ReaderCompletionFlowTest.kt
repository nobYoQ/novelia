package cc.novelia.app.ui.reader

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.data.storage.LocalStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderCompletionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun finalScrollScreenCompletesTheBookWithChapterEndButtonsHidden() = verifyCompletion("scroll")

    @Test fun finalAutomaticPageCompletesTheBookWithoutChangingItsRestoreAnchor() = verifyCompletion("auto")

    @Test fun alreadyCompletedBooksLoseOldBadgesWithoutReopeningButKeepFutureUpdates() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val ref = BookRef("local", "completed-badge-upgrade")
        val title = "已读更新提示修复"
        val readAt = System.currentTimeMillis()
        val position = Position("last", index = 1, updatedAt = readAt, chapterIndex = 0,
            chapterCount = 1, paragraphCount = 1, chapterCompleted = true)
        try {
            compose.runOnIdle {
                app.store.saveBook(BookCard(ref, title, total = 1))
                app.store.update { state -> state.copy(reducedMotion = true,
                    books = state.books.map { if(it.book.ref == ref) it.copy(hasUpdates = true) else it },
                    positions = state.positions + (ref.key to position),
                    bookUpdates = state.bookUpdates + (ref.key to BookUpdateInfo(checkedAt = readAt - 1_000,
                        translations = mapOf("gpt" to 1)))) }
            }
            compose.onNodeWithText("本地文件").performClick()
            compose.waitUntil(10_000) { app.store.state.value.books.any { it.book.ref == ref && !it.hasUpdates } }
            compose.onNodeWithTag("book-reading-progress-${ref.key}", useUnmergedTree = true)
                .assertContentDescriptionEquals("已读 100%")
            compose.onNode(hasText(title) and hasText("有更新")).assertDoesNotExist()
            assertEquals(position, app.store.state.value.positions[ref.key])
            runBlocking { app.store.flush() }
            val persisted = LocalStore(compose.activity).state.value
            assertFalse(persisted.books.single { it.book.ref == ref }.hasUpdates)
            assertFalse(persisted.bookUpdates.getValue(ref.key).hasChanges)
            assertEquals(readAt - 1_000, persisted.bookUpdates.getValue(ref.key).latestTranslationAt(listOf("gpt")))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(title) and hasText("有更新")).assertDoesNotExist()

            compose.runOnIdle {
                app.store.update { state -> state.copy(
                    books = state.books.map { if(it.book.ref == ref) it.copy(hasUpdates = true) else it },
                    bookUpdates = state.bookUpdates + (ref.key to BookUpdateInfo(checkedAt = readAt + 1_000,
                        translations = mapOf("gpt" to 1)))) }
            }
            compose.onNode(hasText(title) and hasText("译文更新")).assertIsDisplayed()
            assertEquals(position, app.store.state.value.positions[ref.key])
        } finally {
            compose.runOnIdle { app.store.update { previous } }
        }
    }

    private fun verifyCompletion(mode: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val ref = BookRef("local", "completion-$mode-regression")
        val title = "末屏进度测试 $mode"
        try {
            compose.runOnIdle {
                app.store.update { it.copy(reader = ReaderSettings(paginationMode = mode, showPageButtons = true,
                    showScrollPageButtons = false), reducedMotion = true, historyPaused = false,
                    bookSettings = it.bookSettings - ref.key, positions = it.positions - ref.key) }
                app.store.saveDocument(LocalDocument(ref.id, title, "txt", listOf(
                    LocalChapter("last", "最后一章", listOf("旅人沿着森林小路前行，寻找远处的小镇。".repeat(90))))))
                app.store.saveBook(BookCard(ref, title, total = 1))
                app.store.update { state -> state.copy(
                    books = state.books.map { if(it.book.ref == ref) it.copy(hasUpdates = true) else it },
                    bookUpdates = state.bookUpdates + (ref.key to BookUpdateInfo(checkedAt = System.currentTimeMillis(),
                        newChapters = 1, translations = mapOf("sakura" to 1, "gpt" to 1, "youdao" to 1)))) }
            }
            compose.onNodeWithText("本地文件").performClick()
            compose.onNode(hasText(title) and hasText("更新 1 章")).assertExists()
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterCount == 1 }
            compose.runOnIdle { assertFalse("长段落的开头不能被判为读完", app.store.state.value.positions.getValue(ref.key).chapterCompleted) }
            // 最新章刚打开就同步书架进度和更新标记，无需先翻到本章末屏。
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("book-reading-progress-${ref.key}", useUnmergedTree = true)
                .assertContentDescriptionEquals("已读 100%")
            compose.onNode(hasText(title) and hasText("更新 1 章")).assertDoesNotExist()
            compose.onNode(hasText(title) and hasText("译文更新")).assertDoesNotExist()
            compose.runOnIdle { assertFalse(app.store.state.value.positions.getValue(ref.key).chapterCompleted) }
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("阅读设置").fetchSemanticsNodes().isNotEmpty() }
            if(mode == "auto") {
                compose.waitUntil(15_000) { compose.onAllNodesWithTag("reader-page-counter").fetchSemanticsNodes().isNotEmpty() }
                val pages = pageNumbers().second
                assertTrue("测试正文应跨越多页", pages > 1)
                for(page in 2..pages) {
                    compose.onNodeWithText("下一页").performClick()
                    compose.waitUntil(10_000) { pageNumbers().first == page }
                }
            } else {
                compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
                compose.onNodeWithText("返回目录").assertDoesNotExist()
            }
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterCompleted == true }
            val finalScreen = app.store.state.value.positions.getValue(ref.key)
            assertEquals("末屏仍应保存真实段落下标", 1, finalScreen.index)
            assertTrue("末屏必须保留段内位置", finalScreen.textOffset > 0)
            if(mode == "scroll") assertTrue(finalScreen.offset > 0)
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("book-reading-progress-${ref.key}", useUnmergedTree = true)
                .assertContentDescriptionEquals("已读 100%")
            compose.onNode(hasText(title) and hasText("更新 1 章")).assertDoesNotExist()
            compose.onNode(hasText(title) and hasText("有更新")).assertDoesNotExist()
            compose.runOnIdle {
                val saved = app.store.state.value.books.single { it.book.ref == ref }
                assertEquals("在读", saved.status)
                assertFalse(saved.hasUpdates)
                assertEquals(0, app.store.state.value.bookUpdates[ref.key]?.newChapters ?: 0)
                assertFalse(app.store.state.value.bookUpdates[ref.key]?.hasChanges == true)
            }
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("阅读设置").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.runOnIdle {
                val restored = app.store.state.value.positions.getValue(ref.key)
                assertTrue(restored.chapterCompleted)
                assertAnchorEquals(finalScreen, restored)
            }
            // 回看前面的屏幕时保留读完记录，同时保存新的阅读锚点。
            compose.onNodeWithText(if(mode == "auto") "上一页" else "上一屏").performClick()
            compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.textOffset != finalScreen.textOffset }
            compose.runOnIdle { assertTrue(app.store.state.value.positions.getValue(ref.key).chapterCompleted) }
        } finally {
            compose.runOnIdle { app.store.update { previous } }
        }
    }

    private fun pageNumbers(): Pair<Int, Int> = compose.onNodeWithTag("reader-page-counter").fetchSemanticsNode()
        .config[SemanticsProperties.Text].single().text.split('/').map { it.trim().toInt() }.let { it[0] to it[1] }

    private fun assertAnchorEquals(expected: Position, actual: Position) {
        assertEquals(expected.chapterId, actual.chapterId)
        assertEquals(expected.index, actual.index)
        assertEquals(expected.offset, actual.offset)
        assertEquals(expected.textOffset, actual.textOffset)
    }
}
