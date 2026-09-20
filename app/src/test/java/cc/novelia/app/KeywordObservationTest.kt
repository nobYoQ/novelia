package cc.novelia.app

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
