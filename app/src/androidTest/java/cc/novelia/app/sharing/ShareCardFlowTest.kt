package cc.novelia.app.sharing

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.text.Spanned
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ShareCardFlowTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
    private val ref = BookRef("kakuyomu", "novelia-share-card-test")
    private val chapterId = "chapter-7"
    private val quote = "雾里的候船室只剩下了最后一名旅客。"

    private fun awaitLabel(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 35_000
        while (SystemClock.uptimeMillis() < deadline) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.packageName == app.packageName &&
                    (node.contentDescription?.toString() == label || node.text?.contains(label) == true)) return
                // 全新设备首次进入沉浸式阅读时，系统提示会暂时占用活动窗口。
                if (label == quote && (node.packageName == "android" || node.packageName == "com.android.systemui") &&
                    node.isClickable && (node.viewIdResourceName?.endsWith("/ok") == true || node.text == "Got it" || node.text == "知道了")) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
            }
            SystemClock.sleep(150)
        }
        fail("没有打开包含「$label」的页面")
    }

    private fun screenshot(card: ShareCard, withCode: Boolean): Pair<File, Intent> = runBlocking {
        val original = loadArtwork(app, card).render(0, withCode)
        val screen = Bitmap.createBitmap(864, 1536, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        canvas.drawColor(Color.LTGRAY)
        canvas.drawBitmap(original, null, Rect(80, 218, 800, 1118), Paint(Paint.FILTER_BITMAP_FLAG))
        original.recycle()
        val dir = File(app.cacheDir, "share-cards").apply { mkdirs() }
        val file = File.createTempFile("test-chat-", ".jpg", dir)
        file.outputStream().use { assertTrue(screen.compress(Bitmap.CompressFormat.JPEG, 76, it)) }
        screen.recycle()
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        file to Intent(Intent.ACTION_SEND).setPackage(app.packageName).setType("image/jpeg").apply {
            clipData = ClipData.newUri(app.contentResolver, "聊天截图", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test fun forwardedScreenshotsAndCaptionOpenTheirDestinations() {
        runBlocking { app.initialization.await() }
        val post = ShareCard(CardKind.Post, "读书交流", "雾岛上的故事", "青苔", "论坛", "https://forum.novelia.cc/p/1", "扫码看原帖")
        val book = BookCard(ref, "雾岛邮局").shareCard("岛上的最后一间邮局。", null)
        val excerpt = paragraphCard(ref, null, chapterId, book.title, "第七章", quote)
        app.store.cacheChapter(ref, chapterId, Chapter(titleJp = "第七章", paragraphs = listOf(quote), gptParagraphs = listOf(quote)))
        val images = listOf(screenshot(post, true), screenshot(book, true), screenshot(excerpt, true), screenshot(book, false))
        try {
            ActivityScenario.launch<MainActivity>(images[0].second).use { scenario ->
                awaitLabel("分享文章")
                lateinit var original: Intent
                scenario.onActivity { original = it.intent }
                try {
                    scenario.onActivity { it.startActivity(images[1].second) }
                    awaitLabel("分享作品")
                    scenario.onActivity { it.startActivity(images[2].second) }
                    awaitLabel(quote)
                    scenario.onActivity { it.startActivity(images[3].second.putExtra(Intent.EXTRA_TEXT, book.url)) }
                    awaitLabel("分享作品")
                } finally { scenario.onActivity { it.intent = original } }
            }
        } finally {
            images.forEach { it.first.delete() }
            app.store.clearChapterCache(ref)
        }
    }

    @Test fun longSelectionExportsEveryPageAndLocalCardHasNoPublicTarget() = runBlocking {
        val card = paragraphCard(ref, null, chapterId, "雾岛邮局", "第七章", (quote + "\n").repeat(30))
        val art = loadArtwork(app, card)
        assertTrue(art.pageCount > 1)
        val chooser = shareImages(app, art, card, true)
        val payload = requireNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, payload.action)
        val uris = requireNotNull(IntentCompat.getParcelableArrayListExtra(payload, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(art.pageCount, uris.size)
        assertTrue(payload.getStringExtra(Intent.EXTRA_TEXT)!!.contains(card.url!!))
        for (uri in uris) assertEquals(card.url, readCardLink(app.contentResolver, uri))
        val local = paragraphCard(BookRef("local", "private-file"), null, "local-chapter", "本地作品", "第一章", quote)
        val (file, image) = screenshot(local, true)
        try {
            assertNull(local.url)
            assertNull(readCardLink(app.contentResolver, image.clipData!!.getItemAt(0).uri))
        } finally { file.delete() }
    }

    @Test fun forumExcerptPreservesMarkdownFormattingAndOnlyShowsLinkLabels() = runBlocking {
        val markdown = """
            本项目已在[Github](https://github.com/nobYoQ/novelia)上开源
            点击[此处](https://github.com/nobYoQ/novelia/releases)下载 **Release**
            [建议收集][feedback]与*反馈*

            [feedback]: https://forum.novelia.cc/p/1094

            | 版本 | 状态 |
            | --- | --- |
            | 0.4.2 | 已发布 |

            !!隐藏内容!!
        """.trimIndent()
        val excerpt = renderPostExcerpt(app, markdown, Color.GREEN) as Spanned
        assertTrue(excerpt.contains("本项目已在Github上开源\n点击此处下载 Release\n建议收集与反馈"))
        assertTrue(excerpt.contains("版本 · 状态\n0.4.2 · 已发布"))
        assertTrue(excerpt.contains("［剧透］"))
        assertFalse(excerpt.contains("https://"))
        assertFalse(excerpt.contains("隐藏内容"))
        assertEquals(3, excerpt.getSpans(0, excerpt.length, LinkSpan::class.java).size)
        assertEquals(1, excerpt.getSpans(0, excerpt.length, StrongEmphasisSpan::class.java).size)
        assertEquals(1, excerpt.getSpans(0, excerpt.length, EmphasisSpan::class.java).size)

        val card = ShareCard(CardKind.Post, "[v0.4.2] Novelia-面向绿站的原生安卓app", markdown,
            "nobyoq · 2026.09.11", "论坛 / 小说讨论", "https://forum.novelia.cc/p/1", "扫码看原帖", listOf("工具"))
        val artifact = File(app.getExternalFilesDir("share-test"), "forum-markdown.png")
        artifact.outputStream().use { loadArtwork(app, card).writePng(0, true, it) }
        assertTrue(artifact.length() > 0)
    }
}
