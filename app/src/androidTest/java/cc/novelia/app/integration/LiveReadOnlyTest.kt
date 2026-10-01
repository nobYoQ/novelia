package cc.novelia.app.integration

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Comment
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.network.NoveliaApi
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 需显式启用：-e live true。只对公开站点资源发送 GET 请求，不写入账号数据。 */
@RunWith(AndroidJUnit4::class)
class LiveReadOnlyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("screenshots"), "$name.png")
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun publicCatalogAndNativeReadingWorkTogether() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val app = compose.activity.application as NoveliaApplication
        val api = NoveliaApi(null)
        val web = api.webList(0, sort = 1); assertTrue(web.items.isNotEmpty())
        val wenku = api.wenkuList(0); assertTrue(wenku.items.isNotEmpty())
        val detail = api.get<WebDetail>("novel/syosetu/n6093en"); assertTrue(detail.toc.isNotEmpty())
        val chapter = api.chapter(BookRef("syosetu", "n6093en"), "1"); assertTrue(chapter.paragraphs.isNotEmpty())
        api.get<WenkuDetail>("wenku/${wenku.items.first().id}")
        val articles = api.get<Page<Article>>("article", mapOf("page" to "0", "pageSize" to "2", "category" to "Guide")); assertTrue(articles.items.isNotEmpty())
        api.get<Article>("article/${articles.items.first().id}")
        api.get<Page<Comment>>("comment", mapOf("site" to "web-syosetu-n6093en", "page" to "0", "pageSize" to "2"))
        compose.runOnIdle { app.store.update { it.copy(theme = "light") } }
        compose.onNodeWithText("发现").performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithText("热门网络小说").fetchSemanticsNodes().isNotEmpty() }
        shot("discover-live")
        compose.runOnIdle { compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("https://n.novelia.cc/novel/syosetu/n6093en"))) }
        compose.waitUntil(30000) { compose.onAllNodesWithText("开始阅读").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("继续阅读").fetchSemanticsNodes().isNotEmpty() }
        shot("book-live")
        compose.onNode(hasText("开始阅读") or hasText("继续阅读")).performClick()
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("阅读设置").fetchSemanticsNodes().isNotEmpty() }
        shot("reader-live")
        assertTrue(app.store.cachedChapter(BookRef("syosetu", "n6093en"), "1") != null)
    }
}
