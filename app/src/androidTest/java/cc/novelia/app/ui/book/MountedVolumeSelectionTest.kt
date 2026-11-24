package cc.novelia.app.ui.book

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.library.withMountedVolumes
import cc.novelia.app.data.model.*
import cc.novelia.app.files.importing.importLocalDocument
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MountedVolumeSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineSelectionDetachesOrDeletesOnlyCheckedCopies() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        val book = BookCard(BookRef("wenku", UUID.randomUUID().toString()), "分卷多选")
        val sources = List(3) { File.createTempFile("mounted-selection-", ".txt", app.cacheDir).apply { writeText(name) } }
        val refs = runBlocking { sources.map { importLocalDocument(app.store, it, title = "同名分卷").ref } }
        try {
            val position = Position("chapter", index = 4)
            app.store.update { state -> state.copy(books = state.books + SavedBook(book),
                positions = state.positions + (refs[0].key to position),
                downloadLinks = state.downloadLinks + DownloadedBookLink("selection-link", refs[0], book.ref, "第一卷")
            ).withMountedVolumes(book.ref.key, refs.map { it.key }.toSet()) }
            compose.setContent {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val c = remember { AppController(app, nav, scope, SnackbarHostState()) }
                AppInteractionMode(true, true) { NoveliaTheme("light") { WenkuVolumesPanel(c, book, null, false, {}, {}) } }
            }
            fun row(index: Int) = compose.onNodeWithTag("shelf-volume-${refs[index].key}")
            compose.onNodeWithText("多选挂载").performClick()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("已选 3 卷").assertIsDisplayed()
            row(2).performClick()
            compose.onNodeWithText("删除本地副本").performClick()
            compose.onNodeWithText("取消").performClick()
            row(0).assertIsOn()
            compose.onNodeWithText("删除本地副本").performClick()
            compose.onNodeWithText("删除副本").performClick()
            compose.waitUntil(10_000) { app.store.state.value.books.none { it.book.ref in refs.take(2) } &&
                !app.store.documentSource(refs[1].id, "txt").exists() }
            assertEquals(position, app.store.state.value.positions[refs[0].key])
            assertTrue(sources.all { it.isFile })
            assertTrue(app.store.documentSource(refs[2].id, "txt").isFile)
            compose.onNodeWithText("多选挂载").performClick()
            row(2).performClick()
            compose.onNodeWithText("解除挂载").performClick()
            compose.waitUntil(5_000) { app.store.state.value.books.single { it.book.ref == refs[2] }.parentWenkuKey == null }
            assertTrue(app.store.documentSource(refs[2].id, "txt").isFile)
        } finally {
            refs.forEach { app.store.removeDocument(it.id) }
            app.store.update { before }
            sources.forEach { it.delete() }
        }
    }
}
