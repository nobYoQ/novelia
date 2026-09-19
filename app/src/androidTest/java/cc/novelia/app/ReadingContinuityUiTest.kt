package cc.novelia.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.*
import org.junit.Assert.assertEquals
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

    @Test fun volumeEndOpensNextSiblingFromTheSavedVolumeOrder() = withLocalBook { app, ref ->
        val next = BookRef("local", "continuity-ui-next")
        val later = BookRef("local", "continuity-ui-later")
        val parent = BookRef("wenku", "continuity-parent")
        compose.runOnIdle {
            app.store.saveDocument(LocalDocument(next.id, "下一卷", "txt", listOf(LocalChapter("opening", "下一卷 开端", listOf("新的故事。")))))
            app.store.saveBook(BookCard(parent, "测试丛书"))
            app.store.saveBook(BookCard(next, "第三卷"))
            app.store.saveBook(BookCard(later, "第二卷"))
            app.store.update { it.withWenkuVolumes(parent.key, setOf(ref.key, next.key, later.key)).withWenkuVolumeOrder(parent.key, listOf(ref.key, next.key, later.key)) }
        }
        compose.onNodeWithContentDescription("下一章").performClick()
        compose.waitUntil(15_000) { app.store.state.value.positions[ref.key]?.chapterId == "second" }
        compose.onNodeWithContentDescription("下一分卷").performClick()
        compose.onNodeWithText("按书架中的分卷顺序接续：第三卷").assertIsDisplayed()
        compose.onNodeWithText("阅读下一分卷").performClick()
        compose.waitUntil(15_000) { app.store.state.value.positions[next.key]?.chapterId == "opening" }
        assertEquals(null, app.store.state.value.positions[later.key])
    }

    private fun withLocalBook(block: (NoveliaApplication, BookRef) -> Unit) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val ref = BookRef("local", "continuity-ui-book")
        try {
            compose.runOnIdle {
                app.store.update { it.copy(reader = ReaderSettings(paginationMode = "scroll", prefetchChapters = 0), reducedMotion = true, historyPaused = false,
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
