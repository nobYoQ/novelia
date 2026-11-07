package cc.novelia.app.ui.shelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.ReadingHistoryEntry
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
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
            updateAt = time(0, 8, 20))
        val second = BookCard(BookRef("kakuyomu", "history-preview-sea"), "在海风吹过的车站等你",
            updateAt = time(5, 16, 10), novelType = "已完结", attentions = emptyList())
        val third = BookCard(BookRef("local", "history-letter-${System.nanoTime()}"), "给明天的一封信 · 第一卷")
        val parent = BookCard(BookRef("wenku", "history-parent"), "给明天的一封信")
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
        val parentCache = hashName("$account:wenku/${parent.ref.id}")
        try {
            store.metadataCache.write(rainCache, appJson.encodeToString(WebDetail(type = "连载中", toc = listOf(
                TocItem(titleZh = entries[0].chapterTitle, chapterId = "chapter", createAt = time(5, 0, 0)),
                TocItem(titleZh = "第 45 话 · 打烊后的来信", chapterId = "latest", createAt = time(2, 8, 20)),
                TocItem(titleZh = "下一卷"),
            ))))
            store.metadataCache.write(seaCache, appJson.encodeToString(WebDetail(updateAt = second.updateAt, toc = listOf(
                TocItem(titleZh = entries[1].chapterTitle, chapterId = "chapter"),
            ))))
            store.saveDocument(LocalDocument(third.ref.id, third.title, "epub", listOf(
                LocalChapter("intro", "前言", listOf("前言。")),
                LocalChapter("chapter", entries[2].chapterTitle, listOf("这是一封春日来信。")),
                LocalChapter("credit", "BOOK☆WALKER", listOf("版权页")),
            )))
            store.metadataCache.write(parentCache, appJson.encodeToString(WenkuDetail(title = parent.title, introduction = "春日书信文库简介")))
            compose.runOnIdle { store.update { it.copy(
                books = original.books + listOf(SavedBook(first), SavedBook(second), SavedBook(parent), SavedBook(third, parentWenkuKey = parent.ref.key)),
                readingHistory = entries.associateBy { entry -> entry.bookKey }, historyMigrated = true,
                theme = "dark", reducedMotion = true, readerTapTutorialSeen = true,
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
            saveTestScreenshot("history-catalog.png")
            val volumeCover = compose.onNodeWithContentDescription("${third.title} 默认封面", useUnmergedTree = true)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("${third.title} 默认封面"))
            compose.onNodeWithText("最新 BOOK☆WALKER", useUnmergedTree = true).assertDoesNotExist()
            volumeCover.performClick()
            compose.onNodeWithText("文库详情").assertIsDisplayed()
            compose.onNodeWithText("春日书信文库简介").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            compose.onNodeWithContentDescription("《${first.title}》的更多操作").performClick()
            compose.onNodeWithText("删除阅读历史").performClick()
            compose.onNodeWithText("删除阅读历史？").assertIsDisplayed()
            assertTrue(first.ref.key in store.state.value.readingHistory)
            compose.onNodeWithText("取消").performClick()
            val readLabel = "读到 ${entries[2].chapterTitle}"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(readLabel))
            compose.onNodeWithText(readLabel).performClick()
            compose.waitUntil(15_000) { store.state.value.positions[third.ref.key]?.chapterId == "chapter" }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("阅读历史").assertIsDisplayed()
        } finally {
            store.removeDocument(third.ref.id)
            store.metadataCache.remove(rainCache)
            store.metadataCache.remove(seaCache)
            store.metadataCache.remove(parentCache)
            compose.runOnIdle { store.update { it.copy(books = original.books,
                readingHistory = original.readingHistory, historyMigrated = original.historyMigrated,
                positions = it.positions - third.ref.key, readingStatistics = original.readingStatistics,
                readerTapTutorialSeen = original.readerTapTutorialSeen, theme = original.theme,
                updateSnapshots = it.updateSnapshots - parent.ref.key, bookUpdates = it.bookUpdates - parent.ref.key,
                reducedMotion = original.reducedMotion) } }
        }
    }
}
