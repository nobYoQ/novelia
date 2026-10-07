package cc.novelia.app.ui.book

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.*
import cc.novelia.app.files.importDownloadedDocument
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WenkuVolumeReadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clickingCompletedVolumeImportsAndNavigatesDirectlyToReading() = withVolume(false)
    @Test fun clickingImportedVolumeStillReadsWhenSourceDownloadWasAutomaticallyDeleted() = withVolume(true)

    private fun withVolume(importFirst: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        val id = UUID.randomUUID().toString(); val ref = BookRef("wenku", "read-$id")
        val volume = JapaneseVolume("第1卷.txt", total = 1, sakura = 1)
        val detail = WenkuDetail(title = "分卷直读测试", volumeJp = listOf(volume)); val card = detail.card(ref)
        val entry = DownloadEntry(id, volume.volumeId, "$id-zh.txt", "https://example.invalid/file", status = "已完成", sourceBook = ref, sourceCard = card)
        val file = File(app.store.downloadsDir, entry.fileName).apply { writeText("第一章\n分卷直读 $id", Charsets.UTF_8) }
        var local: BookRef? = null
        try {
            app.store.update { it.copy(downloads = it.downloads + entry, deleteDownloadAfterImport = importFirst) }
            if(importFirst) {
                local = runBlocking { importDownloadedDocument(app, entry).ref }
                assertFalse(file.exists())
                assertFalse(app.store.state.value.downloads.any { it.id == id })
            }
            compose.setContent {
                AppInteractionMode(false, true) { NoveliaTheme("light") {
                    val nav = rememberNavController(); val scope = rememberCoroutineScope()
                    val c = remember { AppController(app, nav, scope, SnackbarHostState()) }
                    NavHost(nav, startDestination = "volumes") {
                        composable("volumes") { WenkuVolumesPanel(c, card, detail, false, {}, {}) }
                        composable("reader/{provider}/{id}/{chapter}") { Text("已进入正文", Modifier.testTag("volume-reader")) }
                    }
                } }
            }
            compose.onNodeWithTag("wenku-volume-${volume.volumeId}").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("volume-reader").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("下载小说").assertDoesNotExist()
            val mounted = app.store.state.value.books.first { it.parentWenkuKey == ref.key }.book.ref
            if(local != null) assertEquals(local, mounted)
            local = mounted
            assertTrue(app.store.documentIndex(mounted.id).chapters.isNotEmpty())
        } finally {
            // 首次阅读导入完成后再收集 ID，防止失败的界面断言遗留测试副本。
            val imported = app.store.state.value.books.filter { it.parentWenkuKey == ref.key }.map { it.book.ref } + listOfNotNull(local)
            imported.distinct().forEach { app.store.removeDocument(it.id) }
            file.delete(); app.store.update { before }; runBlocking { app.store.flush() }
        }
    }
}
