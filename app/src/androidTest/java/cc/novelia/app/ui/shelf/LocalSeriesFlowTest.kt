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
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.Position
import cc.novelia.app.files.importing.importLocalDocument
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LocalSeriesFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createsRenamesAndDissolvesASeriesWithIndependentVolumeCoversAndPositions() = runBlocking {
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
            store.update { it.copy(positions = it.positions + (first.key to position), rememberLastShelfFolder = false) }
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
                    val navigation = remember { SavedStateHandle() }
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { AdaptiveLibraryScreen(c, navigation) }
                } }
            }
            compose.onNodeWithContentDescription("新建本地总集").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("测试总集")
            compose.onNodeWithText("保存").performClick()
            compose.onNodeWithTag("local-series-detail").assertIsDisplayed()
            val parent = store.state.value.books.single { it.book.title == "测试总集" }.book.ref
            compose.onNodeWithText("添加分卷").performClick()
            books.forEach { ref ->
                val tag = "mount-volume-${ref.key}"
                compose.onNodeWithTag("wenku-volume-picker").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).performClick()
            }
            compose.onNodeWithText("保存挂载（2）").performClick()
            compose.onNodeWithText("继续阅读 · 总集测试卷 1").assertIsDisplayed()
            val firstCover = compose.onNodeWithTag("volume-cover-${first.key}", useUnmergedTree = true)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("volume-cover-${first.key}") and hasContentDescription("总集测试卷 1 自定义封面"),
                    useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            firstCover.assertIsDisplayed()
            compose.onNodeWithTag("volume-cover-${books[1].key}", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(position, store.state.value.positions[first.key])

            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("更换封面").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("测试总集 自定义封面", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("重命名总集").performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("改名后的总集")
            compose.onNodeWithText("保存").performClick()
            assertEquals("改名后的总集", store.state.value.books.single { it.book.ref == parent }.book.title)
            assertEquals(position, store.state.value.positions[first.key])
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("解散总集").performClick()
            compose.onNodeWithText("解散总集").performClick()
            compose.waitUntil(10_000) { store.state.value.books.none { it.book.ref == parent } }
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
