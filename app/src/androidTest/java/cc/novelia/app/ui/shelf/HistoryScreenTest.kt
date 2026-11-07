package cc.novelia.app.ui.shelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
        val first = BookCard(BookRef("syosetu", "history-preview-rain"), "反派贵族与爱太沉重的英雄们的孤儿院生活",
            updateAt = time(0, 8, 20))
        val second = BookCard(BookRef("kakuyomu", "history-preview-sea"), "为了改变游戏的结局，骑士与魔女踏上了归途",
            updateAt = time(5, 16, 10), novelType = "已完结", attentions = emptyList())
        val third = BookCard(BookRef("local", "history-letter-${System.nanoTime()}"), "見習い魔女のホームステイ、居候先での長い一日")
        val fourth = BookCard(BookRef("hameln", "history-preview-night"), "夜行列车")
        val parent = BookCard(BookRef("wenku", "history-parent"), "给明天的一封信")
        fun entry(book: BookCard, chapter: String, read: Long) =
            ReadingHistoryEntry(book.ref.key, book.title, "chapter", chapter, read * 1000L)
        val entries = listOf(
            entry(first, "第 1 话　与未来的英雄们的生活", time(1, 21, 36)),
            entry(second, "第 8 章 · 末班列车", time(2, 18, 10)),
            entry(third, "序章 · 春日来信", time(4, 12, 30)),
            entry(fourth, "第 2 章　沿途的灯火", time(6, 19, 10)),
        )
        val account = app.session.capture().cacheAccount
        val rainCache = hashName("$account:novel/${first.ref.key}")
        val seaCache = hashName("$account:novel/${second.ref.key}")
        val parentCache = hashName("$account:wenku/${parent.ref.id}")
        val nightCache = hashName("$account:novel/${fourth.ref.key}")
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
            store.metadataCache.write(nightCache, appJson.encodeToString(WebDetail(type = "连载中", toc = listOf(
                TocItem(titleZh = entries[3].chapterTitle, chapterId = "chapter"),
                TocItem(titleZh = "第 3 章　终点站", chapterId = "last"),
            ))))
            compose.runOnIdle { store.update { it.copy(
                books = original.books + listOf(SavedBook(first), SavedBook(second), SavedBook(parent),
                    SavedBook(third, parentWenkuKey = parent.ref.key), SavedBook(fourth)),
                readingHistory = entries.associateBy { entry -> entry.bookKey }, historyMigrated = true,
                theme = "dark", reducedMotion = true, readerTapTutorialSeen = true,
            ) } }
            compose.onNodeWithText("我的").performClick()
            compose.onNodeWithTag("profile-history").performClick()
            val list = compose.onNode(hasScrollToIndexAction())
            list.performScrollToIndex(2)
            val readLine = compose.onNodeWithText(entries[0].chapterTitle, useUnmergedTree = true).assertIsDisplayed()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("第 45 话 · 打烊后的来信", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val latestLine = compose.onNodeWithText("第 45 话 · 打烊后的来信", useUnmergedTree = true).assertIsDisplayed()
            val titleBounds = compose.onNodeWithText(first.title, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val readBounds = readLine.getUnclippedBoundsInRoot()
            val latestBounds = latestLine.getUnclippedBoundsInRoot()
            assertTrue(readBounds.top - titleBounds.bottom >= 9.dp)
            assertTrue(latestBounds.left == readBounds.left && latestBounds.top - readBounds.bottom >= 4.dp)
            val readTime = compose.onNodeWithText("昨天 21:36 读过", useUnmergedTree = true).assertIsDisplayed()
            assertTrue(readTime.getUnclippedBoundsInRoot().top - latestBounds.bottom >= 10.dp)
            compose.onNodeWithText("今天 08:20 更新", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("删除阅读历史").assertDoesNotExist()
            saveTestScreenshot("history-layout.png")
            list.performScrollToNode(hasText("已是最新"))
            compose.onNodeWithText("已是最新", useUnmergedTree = true).assertIsDisplayed()
            compose.onAllNodesWithText(entries[1].chapterTitle, useUnmergedTree = true).assertCountEquals(1)
            val volumeCover = compose.onNodeWithContentDescription("${third.title} 默认封面", useUnmergedTree = true)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("${third.title} 默认封面"))
            compose.onNodeWithText("BOOK☆WALKER", useUnmergedTree = true).assertDoesNotExist()
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
            val readLabel = entries[2].chapterTitle
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
            store.metadataCache.remove(nightCache)
            compose.runOnIdle { store.update { it.copy(books = original.books,
                readingHistory = original.readingHistory, historyMigrated = original.historyMigrated,
                positions = it.positions - third.ref.key, readingStatistics = original.readingStatistics,
                readerTapTutorialSeen = original.readerTapTutorialSeen, theme = original.theme,
                updateSnapshots = it.updateSnapshots - parent.ref.key, bookUpdates = it.bookUpdates - parent.ref.key,
                reducedMotion = original.reducedMotion) } }
        }
    }
}
