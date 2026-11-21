package cc.novelia.app.ui.reader

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.library.withVolumeOrder
import cc.novelia.app.data.library.withMountedVolumes
import cc.novelia.app.data.library.withReadingStatus
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingContinuityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun wholeBookSearchFindsAndNavigatesToParagraphInAnotherLocalChapter() = withLocalBook { app, ref ->
        compose.onNodeWithContentDescription("搜索本章").performClick()
        compose.onNodeWithText("整本搜索（本地 / 已缓存章节）").performClick()
        compose.onNodeWithTag("book-search-query").performTextInput("独特检索词")
        compose.onNodeWithText("搜索", substring = false).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("第 2 章 · 第二章 归途").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("第 2 章 · 第二章 归途").performClick()
        compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
        compose.onNodeWithText("这里出现独特检索词，可以准确跳转。", substring = true).assertIsDisplayed()
    }

    @Test fun volumeEndTracksMountedCompletionAndOpensTheNextSiblingInOrder() = withLocalBook { app, ref ->
        val next = BookRef("local", "continuity-ui-next")
        val later = BookRef("local", "continuity-ui-later")
        val parent = BookRef("wenku", "continuity-parent")
        compose.runOnIdle {
            app.store.saveDocument(LocalDocument(next.id, "下一卷", "txt", listOf(LocalChapter("opening", "下一卷 开端", listOf("新的故事。")))))
            app.store.saveBook(BookCard(parent, "测试丛书", publishedVolumeCount = 5))
            app.store.saveBook(BookCard(next, "第三卷"))
            app.store.saveBook(BookCard(later, "第二卷"))
            app.store.update { it.withMountedVolumes(parent.key, setOf(ref.key, next.key, later.key)).withVolumeOrder(parent.key, listOf(ref.key, next.key, later.key)) }
        }
        compose.onNodeWithContentDescription("下一章").performClick()
        compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.let { it.chapterId == "second" && it.chapterCount == 2 } == true }
        compose.onNodeWithContentDescription("下一分卷").performClick()
        compose.onNodeWithText("切换分卷").assertIsDisplayed()
        assertNotEquals("读完", app.store.state.value.books.single { it.book.ref == ref }.status)
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithTag("reader-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitUntil(10_000) { app.store.state.value.positions[ref.key]?.chapterCompleted == true }
        compose.onNodeWithContentDescription("下一分卷").performClick()
        compose.onNodeWithText("按书架中的分卷顺序接续：第三卷").assertIsDisplayed()
        assertEquals("读完", app.store.state.value.books.single { it.book.ref == ref }.status)
        assertEquals("在读", app.store.state.value.books.single { it.book.ref == parent }.status)
        compose.onNode(hasText("阅读下一分卷") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) { app.store.state.value.positions[next.key]?.chapterId == "opening" }
        compose.waitUntil(2_500) { app.store.state.value.books.single { it.book.ref == next }.status == "读完" }
        assertEquals("在读", app.store.state.value.books.single { it.book.ref == parent }.status)
        compose.runOnIdle { app.store.update { it.withReadingStatus(setOf(later.key), "读完") } }
        assertEquals("读完", app.store.state.value.books.single { it.book.ref == parent }.status)
        assertEquals(null, app.store.state.value.positions[later.key])
    }

    private fun withLocalBook(block: (NoveliaApplication, BookRef) -> Unit) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val ref = BookRef("local", "continuity-ui-book")
        try {
            compose.runOnIdle {
                app.store.update { it.copy(autoReadingStatus = true, reader = ReaderSettings(paginationMode = "scroll", prefetchChapters = 0), reducedMotion = true, historyPaused = false,
                    positions = it.positions - ref.key - "local/continuity-ui-next", bookSettings = it.bookSettings - ref.key) }
                app.store.saveDocument(LocalDocument(ref.id, "连续阅读测试", "txt", listOf(
                    LocalChapter("first", "第一章 出发", listOf("旅人出发了。")),
                    LocalChapter("second", "第二章 归途", listOf("第一段。".repeat(50), "这里出现独特检索词，可以准确跳转。", "旅人继续前行。".repeat(100)))
                )))
                app.store.saveBook(BookCard(ref, "连续阅读测试"))
            }
            compose.onNodeWithText("本地文件").performClick()
            compose.onNodeWithText("连续阅读测试").performClick()
            compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterId == "first" }
            block(app, ref)
        } finally { compose.runOnIdle { app.store.update { previous } } }
    }
}
