package cc.novelia.app.ui.shelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.ReadingHistoryEntry
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun localHistoryShowsBothTimesAndKeepsDeletionBehindTheMenu() {
        val app = compose.activity.application as NoveliaApplication
        val store = app.store
        val original = store.state.value
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun time(daysAgo: Long, hour: Int, minute: Int) =
            today.minusDays(daysAgo).atTime(hour, minute).atZone(zone).toEpochSecond()
        val first = BookCard(BookRef("syosetu", "history-preview-rain"), "雨停之后的书店",
            updateAt = time(0, 8, 20), novelType = "连载中", attentions = emptyList())
        val second = BookCard(BookRef("kakuyomu", "history-preview-sea"), "在海风吹过的车站等你",
            updateAt = time(5, 16, 10), novelType = "已完结", attentions = emptyList())
        val third = BookCard(BookRef("local", "history-preview-letter"), "给明天的一封信")
        fun entry(book: BookCard, chapter: String, read: Long) =
            ReadingHistoryEntry(book.ref.key, book.title, "chapter", chapter, read * 1000L)
        val entries = listOf(
            entry(first, "第 42 话 · 夜雨初晴", time(1, 21, 36)),
            entry(second, "第 8 章 · 末班列车", time(2, 18, 10)),
            entry(third, "序章 · 春日来信", time(4, 12, 30)),
        )
        val account = app.session.capture().cacheAccount
        val rainCache = hashName("$account:novel/${first.ref.key}")
        val seaCache = hashName("$account:novel/${second.ref.key}")
        try {
            store.metadataCache.write(rainCache, appJson.encodeToString(WebDetail(updateAt = first.updateAt, toc = listOf(
                TocItem(titleZh = entries[0].chapterTitle, chapterId = "chapter"),
                TocItem(titleZh = "第 45 话 · 打烊后的来信", chapterId = "latest"),
                TocItem(titleZh = "下一卷"),
            ))))
            store.metadataCache.write(seaCache, appJson.encodeToString(WebDetail(updateAt = second.updateAt, toc = listOf(
                TocItem(titleZh = entries[1].chapterTitle, chapterId = "chapter"),
            ))))
            compose.runOnIdle { store.update { it.copy(
                books = original.books + listOf(first, second, third).map { book -> SavedBook(book) },
                readingHistory = entries.associateBy { entry -> entry.bookKey }, historyMigrated = true,
                theme = "light", reducedMotion = true,
            ) } }
            compose.onNodeWithText("我的").performClick()
            compose.onNodeWithTag("profile-history").performClick()
            compose.onNodeWithText("读到 第 42 话 · 夜雨初晴", useUnmergedTree = true).assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("最新 第 45 话 · 打烊后的来信", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("（已是最新）", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("最新 第 45 话 · 打烊后的来信", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("（已是最新）", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("最新 ${entries[1].chapterTitle}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose.onNodeWithText("昨天 21:36 读过", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("今天 08:20 更新", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("删除阅读历史").assertDoesNotExist()
            saveTestScreenshot("history-latest-chapter.png")
            compose.onNodeWithContentDescription("《${first.title}》的更多操作").performClick()
            compose.onNodeWithText("删除阅读历史").performClick()
            compose.onNodeWithText("删除阅读历史？").assertIsDisplayed()
            assertTrue(first.ref.key in store.state.value.readingHistory)
        } finally {
            store.metadataCache.remove(rainCache)
            store.metadataCache.remove(seaCache)
            compose.runOnIdle { store.update { it.copy(books = original.books,
                readingHistory = original.readingHistory, historyMigrated = original.historyMigrated,
                theme = original.theme, reducedMotion = original.reducedMotion) } }
        }
    }
}
