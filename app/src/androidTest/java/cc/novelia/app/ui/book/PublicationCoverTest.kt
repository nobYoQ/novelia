package cc.novelia.app.ui.book

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.model.WenkuVolume
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class PublicationCoverTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication

    private fun openBook(ref: BookRef, web: WebDetail, edition: WenkuDetail, eInk: Boolean = false) {
        runBlocking { app.initialization.await() }
        compose.setContent {
            val nav = rememberNavController()
            val scope = rememberCoroutineScope()
            val c = remember { AppController(app, nav, scope, SnackbarHostState()).also {
                it.uiResults.webDetails.put(it.detailKey("novel/${ref.key}"), web)
                it.uiResults.wenkuDetails.put(it.detailKey("wenku/${web.wenkuId}"), edition)
            } }
            AppInteractionMode(eInk, false) { NoveliaTheme("light") { BookScreen(c, ref) } }
        }
    }

    @Test fun editionWithOnlyUploadedFilesStillOffersLocalPictures() {
        val ref = BookRef("syosetu", UUID.randomUUID().toString())
        openBook(ref, WebDetail(wenkuId = ref.id, titleZh = "空卷目测试"), WenkuDetail(volumeZh = listOf("上传卷.epub")), eInk = true)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("空卷目测试 默认封面").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("更换封面").performClick()
        compose.onNodeWithText("文库版出版封面").performClick()
        compose.onNodeWithText("文库版暂无可选的出版封面").assertIsDisplayed()
        compose.onNodeWithText("本地图片").assertHasClickAction()
        compose.onNodeWithText("上传卷.epub").assertDoesNotExist()
    }

    @Test fun linkedNovelCanChooseAndRestorePublishedCoversWithImageFallback() {
        val ref = BookRef("syosetu", UUID.randomUUID().toString())
        val edition = BookRef("wenku", ref.id)
        val title = "测试网络小说"
        val detail = WebDetail(wenkuId = edition.id, titleZh = title)
        val host = "https://covers.example.invalid"
        val publications = WenkuDetail(volumes = listOf(
            WenkuVolume(title = "无封面卷", cover = "file:///unrelated"),
            WenkuVolume(titleZh = "第一卷", coverHires = "$host/large", cover = "$host/missing-preview"),
            WenkuVolume(title = "第二卷", coverHires = "$host/missing", cover = "$host/small"),
            WenkuVolume(title = "第三卷", cover = "$host/broken"),
        ))
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            return ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
        }
        val large = png(Color.RED)
        val small = png(Color.BLUE)
        val largeRequests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val bytes = when(chain.request().url.encodedPath) {
                "/large" -> large.also { largeRequests.incrementAndGet() }
                "/small" -> small
                "/broken" -> "损坏图片".toByteArray()
                else -> null
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if(bytes == null) 404 else 200).message("fixture").body((bytes ?: byteArrayOf()).toResponseBody()).build()
        }.build()
        val originalLoader = app.imageLoader
        val testLoader = ImageLoader.Builder(app).okHttpClient(client).diskCache(null).build()
        fun awaitDescription(label: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("$title $label").fetchSemanticsNodes().isNotEmpty()
        }
        fun openPublications() {
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("更换封面").performClick()
            compose.onNodeWithText("本地图片").assertIsDisplayed()
            compose.onNodeWithText("文库版出版封面").performClick()
        }
        fun savedBytes() = runBlocking { app.store.bookCovers.observe(ref).first()?.file?.readBytes() }
        try {
            Coil.setImageLoader(testLoader)
            openBook(ref, detail, publications)
            awaitDescription("默认封面")
            openPublications()
            compose.onNodeWithText("无封面卷").assertDoesNotExist()
            compose.waitUntil(10_000) { largeRequests.get() > 0 }
            compose.onNodeWithText("第一卷").performClick()
            awaitDescription("自定义封面")
            assertArrayEquals(large, savedBytes())
            openPublications()
            compose.onNodeWithText("第三卷").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("点按重试", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertArrayEquals(large, savedBytes())
            compose.onNodeWithText("第二卷").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("选择出版卷封面").fetchSemanticsNodes().isEmpty() }
            assertArrayEquals(small, savedBytes())
            assertNull(runBlocking { app.store.bookCovers.observe(edition).first() })
            compose.onNodeWithContentDescription("更多操作").performClick()
            compose.onNodeWithText("恢复默认封面").performClick()
            awaitDescription("默认封面")
            assertNull(savedBytes())
        } finally {
            Coil.setImageLoader(originalLoader)
            testLoader.shutdown()
            app.store.bookCovers.restoreDefault(ref)
        }
    }
}
