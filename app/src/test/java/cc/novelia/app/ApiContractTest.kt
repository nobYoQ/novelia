package cc.novelia.app

import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.network.NoveliaApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ApiContractTest {
    @Test fun guestSearchUsesAllProvidersAndGeneralAudience() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[{"providerId":"syosetu","novelId":"n1","titleJp":"Test"}]}"""))
            val api = NoveliaApi(null, server.url("/api/").toString())
            val page = api.webList(0, "中文 空格")
            val request = server.takeRequest()
            assertEquals("1", request.requestUrl!!.queryParameter("level"))
            assertEquals(providers.keys.toSet(), request.requestUrl!!.queryParameter("provider")!!.split(',').toSet())
            assertEquals("中文 空格", request.requestUrl!!.queryParameter("query"))
            assertNull(request.getHeader("Authorization"))
            assertEquals("n1", page.items.single().novelId)
        }
    }
    @Test fun downloadEscapesVolumeOnceAndKeepsRepeatedEngines() {
        val api = NoveliaApi(null)
        val value = api.downloadUrl(BookRef("wenku", "abc"), "一卷 空格.epub", "zh", listOf("sakura", "gpt"), false, "epub", "中文.epub")
        assertFalse(value.contains("%25E"))
        assertTrue(value.contains("translations=sakura&translations=gpt"))
        assertTrue(value.contains("filename="))
    }
    @Test fun unauthorizedRequestKeepsItsMeaning() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            val api = NoveliaApi(null, server.url("/api/").toString())
            try { api.get<CloudFolders>("user/favored"); fail("Expected authorization failure") } catch(e: ApiException) { assertEquals(401, e.status) }
        }
    }
}
