package cc.novelia.app.ui.downloads

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.library.withVolumeParent
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
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

class DownloadBatchImportTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: AppController

    @Test fun batchImportMountsVolumesPreservesDuplicatesAndRetriesOnlyFailures() = withDownloads { app, parent, entries ->
        val existing = runBlocking { importDownloadedDocument(app.store, entries[0]) }
        val position = Position("0", index = 1, offset = 12, title = "保留的阅读进度")
        app.store.savePosition(existing, position)
        app.store.update { it.withVolumeParent(existing.key, null) }
        val savedPosition = app.store.state.value.positions.getValue(existing.key)
        val restore = showDownloads(app)
        compose.onNodeWithText("批量操作").performClick()
        compose.onNodeWithTag("download-batch-import").assertIsNotEnabled()
        selectRow(entries[0]).performClick()
        compose.onNodeWithText("已选 1 个").assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("已选 1 个").assertIsDisplayed()
        compose.onNodeWithText("全选已完成").performClick()
        compose.onNodeWithText("已选 4 个").assertIsDisplayed()
        selectRow(entries.last()).assertIsEnabled().assertIsOff()
        selectRow(entries.last()).performClick()
        compose.onNodeWithText("已选 5 个").assertIsDisplayed()
        compose.onNodeWithText("导入书架（4）").assertIsDisplayed()
        compose.onNodeWithTag("download-batch-import").performClick()
        waitForSummary("成功 2 · 重复 1 · 失败 1 · 尚未处理 0")
        compose.runOnIdle {
            assertEquals("downloads", controller.nav.currentDestination?.route)
            val state = app.store.state.value
            val savedParent = state.books.single { it.book.ref == parent.book.ref }
            assertEquals(parent.copy(volumesExpanded = true), savedParent)
            val mounted = state.books.filter { it.parentWenkuKey == parent.book.ref.key }
            assertEquals(2, mounted.size)
            assertTrue(mounted.all { it.folder == parent.folder })
            assertEquals(1, state.books.count { it.book.ref == existing })
            assertEquals(savedPosition, state.positions[existing.key])
            assertNull(state.books.single { it.book.title == entries[3].title }.parentWenkuKey)
        }
        val importedKeys = app.store.state.value.books.filter { it.book.ref.isLocal }.map { it.book.ref.key }.toSet()
        File(app.store.downloadsDir, entries[2].fileName).writeText("第一章 修复\n失败项修复后的正文 ${entries[2].id}", Charsets.UTF_8)
        compose.onNodeWithTag("import-results").performClick()
        compose.onNodeWithText("仅重试失败项").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("成功 3 · 重复 1 · 失败 0 · 尚未处理 0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("仅重试失败项").assertIsNotEnabled()
        compose.runOnIdle {
            val books = app.store.state.value.books
            assertEquals(4, books.count { it.book.ref.isLocal })
            assertTrue(books.map { it.book.ref.key }.containsAll(importedKeys))
            assertEquals(3, books.count { it.parentWenkuKey == parent.book.ref.key })
            assertEquals(savedPosition, app.store.state.value.positions[existing.key])
            assertEquals("downloads", controller.nav.currentDestination?.route)
        }
    }

    @Test fun importingOnlySelectedDownloadLeavesOtherFilesUntouchedAndReadingReusesIt() = withDownloads { app, parent, entries ->
        showDownloads(app)
        compose.onNodeWithText("批量操作").performClick()
        selectRow(entries[1]).performClick()
        compose.onNodeWithTag("download-batch-import").performClick()
        waitForSummary("成功 1 · 重复 0 · 失败 0 · 尚未处理 0")
        val volume = app.store.state.value.books.single { it.book.ref.isLocal }
        assertEquals(entries[1].title, volume.book.title)
        assertEquals(parent.book.ref.key, volume.parentWenkuKey)
        compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-read-${entries[1].id}"))
        compose.onNodeWithTag("download-read-${entries[1].id}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("阅读测试页").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle {
            assertEquals(1, app.store.state.value.books.count { it.book.ref.isLocal })
            assertEquals(volume.book.ref.id, controller.nav.currentBackStackEntry?.arguments?.getString("id"))
            assertEquals(parent.book.ref.key, app.store.state.value.books.single { it.book.ref == volume.book.ref }.parentWenkuKey)
        }
    }

    @Test fun batchDeleteConfirmsMixedStatusesAndPreservesImportedBookAndUnselectedDownloads() = withDownloads { app, _, entries ->
        val imported = runBlocking { importDownloadedDocument(app.store, entries[0]) }
        val position = Position("0", index = 1, offset = 7)
        app.store.savePosition(imported, position)
        val savedPosition = app.store.state.value.positions.getValue(imported.key)
        val savedBook = app.store.state.value.books.single { it.book.ref == imported }
        showDownloads(app)
        compose.onNodeWithText("批量操作").performClick()
        compose.onNodeWithTag("download-batch-delete").assertIsNotEnabled()
        selectRow(entries.last()).performClick()
        compose.onNodeWithTag("download-batch-import").assertIsNotEnabled()
        selectRow(entries[0]).performClick()
        compose.onNodeWithTag("download-batch-delete").performClick()
        compose.onNodeWithText("删除 2 个下载？").assertIsDisplayed()
        compose.onNode(hasText("取消") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(entries, app.store.state.value.downloads) }
        compose.onNodeWithTag("download-batch-delete").performClick()
        compose.onNodeWithText("删除下载").performClick()
        compose.waitUntil(10_000) { app.store.state.value.downloads.size == 3 }
        compose.onNodeWithText("批量操作").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(entries.slice(1..3), app.store.state.value.downloads)
            assertFalse(File(app.store.downloadsDir, entries[0].fileName).exists())
            assertFalse(File(app.store.downloadsDir, entries.last().fileName).exists())
            assertTrue(File(app.store.downloadsDir, entries[1].fileName).exists())
            assertEquals(savedBook, app.store.state.value.books.single { it.book.ref == imported })
            assertEquals(savedPosition, app.store.state.value.positions[imported.key])
            assertTrue(app.store.documentSource(imported.id, "txt").exists())
        }
    }

    @Test fun allPendingDownloadsCanBeSelectedAndBatchModeCanBeCancelled() = withDownloads { app, _, entries ->
        app.store.update { it.copy(downloads = entries.map { entry -> entry.copy(status = "已暂停") }) }
        showDownloads(app)
        compose.onNodeWithText("批量操作").assertIsEnabled().performClick()
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 5 个").assertIsDisplayed()
        compose.onNodeWithTag("download-batch-import").assertIsNotEnabled()
        compose.onNodeWithTag("download-batch-export").assertIsNotEnabled()
        compose.onNodeWithTag("download-batch-share").assertIsNotEnabled()
        compose.onNodeWithTag("download-batch-delete").assertIsEnabled()
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("已选 0 个").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("download-batch-delete").assertDoesNotExist()
        compose.runOnIdle { assertEquals(5, app.store.state.value.downloads.size) }
    }

    private fun selectRow(entry: DownloadEntry): SemanticsNodeInteraction {
        compose.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-select-${entry.id}"))
        return compose.onNodeWithTag("download-select-${entry.id}")
    }

    private fun waitForSummary(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun showDownloads(app: NoveliaApplication): StateRestorationTester = StateRestorationTester(compose).also { restore ->
        restore.setContent {
            AppInteractionMode(false, true) {
                NoveliaTheme("light") {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    controller = remember { AppController(app, nav, scope, SnackbarHostState()) }
                    NavHost(nav, startDestination = "downloads") {
                        composable("downloads") { DownloadsScreen(controller) }
                        composable("reader/{provider}/{id}/{chapter}") { Text("阅读测试页") }
                    }
                }
            }
        }
    }

    private fun withDownloads(block: (NoveliaApplication, SavedBook, List<DownloadEntry>) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        val id = "download-import-${UUID.randomUUID()}"
        val parent = SavedBook(BookCard(BookRef("wenku", id), "已有文库收藏"), folder = "我的文库", pinned = true, status = "读完", addedAt = 1234)
        val entries = (1..5).map { index ->
            DownloadEntry("$id-$index", "测试分卷 $index", "$id-$index.txt", "https://example.invalid/$index",
                status = if(index == 5) "等待下载" else "已完成",
                sourceBook = if(index == 4) BookRef("syosetu", id) else parent.book.ref,
                sourceCard = if(index == 2) parent.book.copy(title = "下载时的旧标题") else null)
        }
        try {
            entries.filterIndexed { index, _ -> index != 2 }.forEach { entry ->
                File(app.store.downloadsDir, entry.fileName).writeText("第一章 测试\n测试文件正文 ${entry.id}\n第二段正文", Charsets.UTF_8)
            }
            app.store.update { it.copy(books = listOf(parent), downloads = entries, folders = listOf("默认收藏", parent.folder), positions = emptyMap()) }
            // LocalStore 会为收藏夹补齐稳定 ID；基准取保存后的条目，继续完整比较导入前后资料。
            block(app, app.store.state.value.books.single { it.book.ref == parent.book.ref }, entries)
        } finally {
            compose.runOnIdle {
                app.store.state.value.books.filter { it.book.ref.isLocal && before.books.none { old -> old.book.ref == it.book.ref } }
                    .forEach { app.store.removeDocument(it.book.ref.id) }
                app.store.update { before }
                // 合成资料也进入同步副本，必须能在下次启动时通过校验。
                app.store.state.value.syncReplica.validate()
            }
            entries.forEach { File(app.store.downloadsDir, it.fileName).delete() }
            runBlocking { app.store.flush() }
        }
    }
}
