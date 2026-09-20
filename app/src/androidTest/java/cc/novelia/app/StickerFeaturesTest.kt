package cc.novelia.app

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.*
import cc.novelia.app.reader.ReadAloudService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StickerFeaturesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun openMenu(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        // Compose idleness does not include the platform Dialog window transition.
        android.os.SystemClock.sleep(350)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("sticker-screenshots"), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun defaultCoverReaderZoomAndEventFeedbackWorkThroughTheApp() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val previousSpeech = ReadAloudService.status.value
        val id = "sticker-preview-${UUID.randomUUID()}"
        val ref = BookRef("local", id)
        val imageId = "b".repeat(64)
        val entry = DownloadEntry(id, "森林来信", "$id.txt", "https://example.invalid/$id", "下载中", 70)
        val download = File(app.store.downloadsDir, entry.fileName)
        try {
            download.writeText("第一章 林间\n这是插图与贴纸验收用的本地小说。$id", Charsets.UTF_8)
            val bytes = app.resources.openRawResource(R.drawable.midori_reading).use { it.readBytes() }
            compose.runOnIdle {
                app.store.saveDocument(LocalDocument(id, "小绿的阅读日记", "epub", listOf(LocalChapter("first", "第一章 午后的书页", listOf("novelia-image:$imageId", "窗外的阳光照在书页上，又到了阅读的时间。"))), images = mapOf(imageId to Base64.getEncoder().encodeToString(bytes))))
                app.store.saveBook(BookCard(ref, "小绿的阅读日记", subtitle = "EPUB · 1 章"))
                app.store.update { it.copy(theme = "light", reducedMotion = true, reader = ReaderSettings(), downloads = listOf(entry)) }
            }
            compose.onNodeWithText("本地文件").performClick()
            compose.onNodeWithText("小绿的阅读日记").performScrollTo()
            compose.onNodeWithContentDescription("小绿的阅读日记 默认封面").assertIsDisplayed()
            screenshot("v2-default-covers")
            compose.onNodeWithText("小绿的阅读日记").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("小说插图").fetchSemanticsNodes().isNotEmpty() }
            val before = compose.onNodeWithContentDescription("小说插图").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("小说插图").performSemanticsAction(SemanticsActions.OnLongClick)
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("放大插图").fetchSemanticsNodes().isNotEmpty() }
            screenshot("v2-illustration-fit")
            compose.onNodeWithContentDescription("放大插图").performClick()
            compose.onNodeWithText("150%").assertIsDisplayed()
            screenshot("v2-illustration-zoom")
            compose.onNodeWithContentDescription("关闭插图").performClick()
            assertEquals(before, compose.onNodeWithContentDescription("小说插图").fetchSemanticsNode().boundsInRoot)
            // Exercise the renderer's real service status without starting audible TTS in a UI test.
            compose.runOnIdle { ReadAloudService.status.value = ReadAloudService.SLEEP_TIMER_FINISHED }
            compose.onNodeWithContentDescription("朗读定时已结束，打开朗读设置").performClick()
            compose.onNodeWithText("朗读定时已结束").assertIsDisplayed()
            compose.onNodeWithText("设置").performScrollTo().assertIsDisplayed()
            screenshot("v2-sleep-timer")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("我的").performClick()
            openMenu("下载管理")
            compose.onNodeWithText("下载中 · 70%").assertIsDisplayed()
            compose.runOnIdle { app.store.update { it.copy(downloads = listOf(entry.copy(status = "已完成", progress = 100))) } }
            compose.onNodeWithText("「森林来信」下载完成").assertIsDisplayed()
            screenshot("v2-download-complete")
            compose.onNodeWithContentDescription("关闭提示").performClick()
            compose.onNodeWithText("开始阅读").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("这是插图与贴纸验收用的本地小说。", substring = true).fetchSemanticsNodes().isNotEmpty() }
            screenshot("v2-import-complete")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("返回").performClick()
            openMenu("阅读与外观")
            compose.onNodeWithText("减少动态效果").assertIsDisplayed()
            compose.onNodeWithText("减少页面切换、列表变化与按压动效").assertDoesNotExist()
            screenshot("v2-settings")
            compose.onNodeWithContentDescription("返回").performClick()
            openMenu("帮助与关于")
            repeat(5) { compose.onNodeWithText("Android ${BuildConfig.VERSION_NAME} · 非官方客户端").performClick() }
            compose.onNodeWithContentDescription("小绿向你招手").assertIsDisplayed()
            screenshot("v2-about-wave")
            compose.onNodeWithContentDescription("返回").performClick()
        } finally {
            compose.runOnIdle {
                ReadAloudService.status.value = previousSpeech
                val added = app.store.state.value.books.filter { it.book.ref.isLocal && previous.books.none { old -> old.book.ref == it.book.ref } }
                added.forEach { app.store.removeDocument(it.book.ref.id) }
                app.store.update { previous }
            }
            download.delete()
        }
    }
}
