package cc.novelia.app.ui.notes

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.NoteSelection
import cc.novelia.app.sharing.readCardLink
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test fun existingBookmarkSavesAllExcerptPagesWithTheChapterQr() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        val resolver = app.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val id = MediaStore.Images.Media._ID
        fun savedIds() = resolver.query(collection, arrayOf(id), "${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf("Pictures/Novelia/"), null)!!.use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
        val existingImages = savedIds()
        val note = Note("bookmark-share", "kakuyomu/bookmark-share", "chapter-4", 0,
            "还记得我们出发的那天。\n".repeat(20), "只留在笔记里的文字", bookTitle = "测试书籍", chapterTitle = "第四章")
        try {
            app.store.update { it.copy(notes = listOf(note)) }
            compose.setContent { NoveliaTheme("light") {
                NotesScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }))
            } }
            compose.onNodeWithText("分享摘录图片").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("分享图片预览").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("保存图片").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithText("张图片到相册 Novelia", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val saved = savedIds() - existingImages
            assertTrue(saved.size > 1)
            saved.forEach { image ->
                assertEquals("https://n.novelia.cc/novel/kakuyomu/bookmark-share/chapter-4", readCardLink(resolver, ContentUris.withAppendedId(collection, image)))
            }
        } finally {
            (savedIds() - existingImages).forEach { resolver.delete(ContentUris.withAppendedId(collection, it), null, null) }
            app.store.update { before }
        }
    }
}
