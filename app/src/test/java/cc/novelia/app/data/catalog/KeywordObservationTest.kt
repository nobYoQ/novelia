package cc.novelia.app.data.catalog

import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.network.NoveliaApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class KeywordObservationTest {
    @Test fun aResultPageSubmitsOneDeduplicatedBatch() {
        val batches = mutableListOf<List<String>>()
        val api = NoveliaApi(null, onKeywords = { batches += it.toList() })
        api.observeKeywords(Page(1, (0 until 20).map {
            WebOutline(providerId = "syosetu", novelId = "n$it", keywords = listOf("共通", "标签$it"))
        }))
        assertEquals(1, batches.size)
        assertEquals(21, batches.single().size)
        assertEquals("共通", batches.single().first())
    }

    @Test fun normalAndCloudListsLearnKeywordsWithoutAnyAdditionalRequest() = runBlocking {
        MockWebServer().use { server ->
            val observed = mutableListOf<String>()
            val api = NoveliaApi(null, server.url("/api/").toString(), onKeywords = { observed += it })
            listOf("novel", "user/favored-web/folder").forEach { path ->
                server.enqueue(MockResponse().setBody("""{"items":[{"providerId":"syosetu","novelId":"n1","keywords":["ハーレム","ヤンデレ"]}]}"""))
                api.get<Page<WebOutline>>(path)
            }
            assertEquals(2, server.requestCount)
            assertEquals(setOf("ハーレム", "ヤンデレ"), observed.toSet())
        }
    }

    @Test fun cachedDetailsCanBeObservedAndCatalogueFailureDoesNotHideContent() {
        val observed = mutableListOf<String>()
        val api = NoveliaApi(null, onKeywords = { observed += it })
        api.observeKeywords(WebDetail(keywords = listOf("ファンタジー")))
        api.observeKeywords(WenkuDetail(keywords = listOf("ラブコメ")))
        assertEquals(listOf("ファンタジー", "ラブコメ"), observed)
        NoveliaApi(null, onKeywords = { error("simulated catalogue failure") }).observeKeywords(WebDetail(keywords = listOf("safe")))
    }
}
