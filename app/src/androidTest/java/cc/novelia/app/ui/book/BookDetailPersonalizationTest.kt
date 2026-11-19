package cc.novelia.app.ui.book

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
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.withReadingTime
import cc.novelia.app.files.importing.importLocalDocument
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class BookDetailPersonalizationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localDetailShowsReadingTimeAndCanReplaceAndRestoreItsCover() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        val source = File.createTempFile("cover-detail-", ".txt", app.cacheDir).apply { writeText("封面测试正文 $name") }
        val image = File.createTempFile("cover-detail-", ".png", app.cacheDir)
        val bitmap = Bitmap.createBitmap(48, 72, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val title = "封面测试"
        val ref = runBlocking {
            app.initialization.await()
            importLocalDocument(app.store, source, title = title).ref
        }
        val before = app.store.state.value
        try {
            app.store.update { state -> state.copy(historyPaused = false, readingStatistics = before.readingStatistics.copy(enabled = true))
                .withReadingTime(ref.key, title, System.currentTimeMillis(), 120_000, before.readingStatistics.revision) }
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                        dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(image)))
                    }
                }
            }
            compose.setContent {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val c = remember(nav, scope) { AppController(app, nav, scope, SnackbarHostState()) }
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    NoveliaTheme("light") { BookScreen(c, ref) }
                }
            }
            fun awaitCover(label: String) = compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("$title $label").fetchSemanticsNodes().isNotEmpty()
            }
            awaitCover("默认封面")
            compose.onNodeWithTag("book-reading-time").assertIsDisplayed().assertTextEquals("累计阅读时长 · 2 分钟")
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("更换封面").performClick()
            awaitCover("自定义封面")
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("恢复默认封面").performClick()
            awaitCover("默认封面")
        } finally {
            app.store.removeDocument(ref.id)
            app.store.update { it.copy(readingStatistics = before.readingStatistics, historyPaused = before.historyPaused) }
            source.delete()
            image.delete()
        }
    }
}
