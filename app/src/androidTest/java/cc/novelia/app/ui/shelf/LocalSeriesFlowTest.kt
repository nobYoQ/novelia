package cc.novelia.app.ui.shelf

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.createShelfFolder
import cc.novelia.app.files.importing.importLocalDocument
import cc.novelia.app.ui.book.localSeriesEditorDestination
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalSeriesFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createsEditsAndDissolvesASeriesWithIndependentVolumeCoversAndPositions() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        app.initialization.await()
        val store = app.store
        val before = store.state.value
        val inputs = List(2) { i -> File.createTempFile("series-volume-", ".txt", app.cacheDir).apply { writeText("分卷 $i · $name") } }
        val image = File.createTempFile("series-cover-", ".png", app.cacheDir)
        val bitmap = Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val books = inputs.mapIndexed { i, file -> importLocalDocument(store, file, title = "总集测试卷 ${i + 1}").ref }
        val first = books.first()
        val position = Position(store.documentIndex(first.id).chapters.first().id, index = 2, offset = 35, title = "原阅读位置")
        try {
            store.update { it.copy(positions = it.positions + (first.key to position), rememberLastShelfFolder = false)
                .createShelfFolder("总集试读") }
            store.bookCovers.replace(first) { image.inputStream() }
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                        dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(image)))
                    }
                }
            }
            compose.setContent {
                AppInteractionMode(eInk = false, reducedMotion = true) { NoveliaTheme("light") {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        NavHost(nav, startDestination = "shelf") {
                            composable("shelf") { AdaptiveLibraryScreen(c, it.savedStateHandle) }
                            localSeriesEditorDestination(c)
                        }
                    }
                } }
            }
            val editor = compose.onNodeWithTag("local-series-editor")
            val detail = compose.onNodeWithTag("local-series-detail")
            fun detailCover(key: String) = hasTestTag("volume-cover-$key") and hasAnyAncestor(hasTestTag("local-series-detail"))
            fun editorItem(matcher: SemanticsMatcher): SemanticsNodeInteraction {
                editor.performScrollToNode(matcher)
                return compose.onNode(matcher).performScrollTo()
            }
            fun editorField(tag: String) = editorItem(hasTestTag(tag))
            fun editorButton(label: String) = editorItem(hasText(label))
            fun libraryTag(query: String, original: String): SemanticsNodeInteraction {
                compose.onNodeWithTag("keyword-library-search").performTextReplacement(query)
                val tag = "library-tag-$original"
                compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                return compose.onNodeWithTag(tag)
            }
            fun volumeAction(key: String, label: String) {
                val action = hasContentDescription(label) and hasAnyAncestor(hasTestTag("edit-series-volume-$key"))
                editor.performScrollToNode(action)
                compose.onNode(action).assertIsDisplayed().performClick()
            }
            fun awaitDetail() {
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("local-series-detail").fetchSemanticsNodes().isNotEmpty() }
                detail.assertIsDisplayed()
            }
            compose.onNodeWithTag("local-folder-picker").performClick()
            compose.onNodeWithText("总集试读").performClick()
            compose.onNodeWithContentDescription("添加到书架").performClick()
            compose.onNodeWithText("导入本地文件").assertIsDisplayed()
            compose.onNodeWithText("新建本地总集").performClick()
            compose.onNodeWithTag("local-series-folder").assertTextContains("总集试读")
            compose.onNodeWithText("选择封面").performClick()
            editorField("local-series-title").performTextInput("测试总集")
            editorField("local-series-authors").performTextInput("作者甲")
            editorField("local-series-tags").performClick()
            libraryTag("奇幻", "ファンタジー").assertIsNotSelected().performClick().assertIsSelected()
            libraryTag("冒険", "冒険").performClick().assertIsSelected()
            compose.onNodeWithText("完成").performClick()
            editorField("local-series-description").performTextInput("总集简介一")
            editorButton("添加分卷").performClick()
            editorField("local-series-volume-search").performTextInput("总集测试卷")
            books.forEach { ref ->
                editorField("pick-series-volume-${ref.key}").performClick().assertIsOn()
            }
            editorButton("完成选择").performClick()
            volumeAction(books[1].key, "上移 总集测试卷 2")
            assertTrue(store.state.value.books.none { it.book.title == "测试总集" })
            assertTrue(store.state.value.books.filter { it.book.ref in books }.all { it.parentWenkuKey == null })
            compose.onNodeWithText("保存").performClick()
            awaitDetail()
            val parent = store.state.value.books.single { it.book.title == "测试总集" }
            assertEquals("总集试读", parent.folder)
            assertEquals("总集简介一", parent.seriesDescription)
            assertEquals(listOf("作者甲"), parent.book.authors)
            assertEquals(listOf("ファンタジー", "冒険"), parent.book.tags)
            assertEquals(books.reversed().map { it.key }, parent.volumeOrder)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("测试总集 自定义封面", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("volume-continue-title", true).assertTextEquals("总集测试卷 1")
            detail.performScrollToNode(hasText("总集简介一"))
            compose.onNodeWithText("总集简介一").assertIsDisplayed()
            detail.performScrollToNode(hasTestTag("shelf-volume-${books[1].key}"))
            compose.onNode(detailCover(books[1].key), useUnmergedTree = true).assertIsDisplayed()
            detail.performScrollToNode(hasTestTag("shelf-volume-${first.key}"))
            val firstCover = detailCover(first.key)
            compose.waitUntil(10_000) {
                compose.onAllNodes(firstCover and hasContentDescription("总集测试卷 1 自定义封面"),
                    useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(firstCover, useUnmergedTree = true).assertIsDisplayed()
            assertEquals(position, store.state.value.positions[first.key])

            compose.onNodeWithText("编辑总集").performClick()
            editorField("local-series-tags").performClick()
            libraryTag("奇幻", "ファンタジー").assertIsSelected().performClick().assertIsNotSelected()
            compose.onNode(hasContentDescription("返回") and hasAnyAncestor(isDialog())).performClick()
            editorField("local-series-tag-ファンタジー").assertIsSelected()
            editorButton("恢复默认封面").performClick()
            editorField("local-series-title").performTextReplacement("不会保存的名称")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("放弃修改").performClick()
            awaitDetail()
            assertEquals("测试总集", store.state.value.books.single { it.book.ref == parent.book.ref }.book.title)
            assertNotNull(store.bookCovers.observe(parent.book.ref).first())

            detail.performScrollToNode(hasText("编辑分卷"))
            compose.onNodeWithText("编辑分卷").performScrollTo().performClick()
            compose.onNodeWithText("分卷 · 2").assertIsDisplayed()
            volumeAction(books[1].key, "移出 总集测试卷 2")
            editorButton("恢复默认封面").performClick()
            editorField("local-series-folder").performClick()
            compose.onNode(hasText(DEFAULT_FOLDER) and hasAnyAncestor(isPopup())).performClick()
            editorField("local-series-title").performTextReplacement("改名后的总集")
            editorField("local-series-tag-冒険").performClick()
            compose.onNodeWithText("保存").performClick()
            awaitDetail()
            val updated = store.state.value.books.single { it.book.ref == parent.book.ref }
            assertEquals("改名后的总集", updated.book.title)
            assertEquals(listOf("ファンタジー"), updated.book.tags)
            assertEquals(DEFAULT_FOLDER, updated.folder)
            assertEquals(DEFAULT_FOLDER, store.state.value.books.single { it.book.ref == first }.folder)
            assertEquals(listOf(first.key), updated.volumeOrder)
            assertNull(store.state.value.books.single { it.book.ref == books[1] }.parentWenkuKey)
            assertNull(store.bookCovers.observe(parent.book.ref).first())
            assertEquals(position, store.state.value.positions[first.key])
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("解散总集").performClick()
            compose.onNodeWithText("解散总集").performClick()
            compose.waitUntil(10_000) { store.state.value.books.none { it.book.ref == parent.book.ref } }
            assertTrue(store.state.value.books.filter { it.book.ref in books }.all { it.parentWenkuKey == null })
            assertEquals(position, store.state.value.positions[first.key])
            books.forEach { assertTrue(store.documentIndex(it.id).chapters.isNotEmpty()) }
        } finally {
            store.state.value.books.filter { it.book.ref.isLocalSeries && it.book.ref !in before.books.map { saved -> saved.book.ref } }
                .forEach { store.bookCovers.restoreDefault(it.book.ref) }
            books.forEach { store.removeDocument(it.id) }
            store.update { before }
            inputs.forEach { it.delete() }
            image.delete()
        }
    }
}
