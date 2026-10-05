package cc.novelia.app.data.network

import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.model.ForumCommunityRulesParser
import cc.novelia.app.data.model.bundledForumCommunityRules
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ForumCommunityRulesTest {
    @get:Rule val folder = TemporaryFolder()
    private val sha = "692916b5ecd76313ee2fcacffc8dc309f342784b"
    private val nextSha = "0123456789abcdef0123456789abcdef01234567"
    // 原站部署 692916b 的静态守则；不包含用户评论或账号资料。
    private val template get() = javaClass.getResource("/forum-community-rules.vue")!!.readText(Charsets.UTF_8)
    private fun html(name: String) = """<html><script type="module" src="/assets/index-$name.js"></script><script type="module" src="https://example.test/analytics.js"></script></html>"""
    private fun bundle(commit: String) = """const config={repository:{url:`https://github.com/auto-novel/forum`,commitSha:`$commit`}};"""
    private fun repository(server: MockWebServer, cache: MetadataCache) = ForumCommunityRulesRepository(cache,
        NoveliaApi(null, server.url("/").toString()), NoveliaApi(null, server.url("/source/").toString()))

    @Test fun deployedTemplatePreservesAllPolicyParagraphsListsAndUpdates() {
        val blocks = ForumCommunityRulesParser.parse(template)
        assertEquals(6, blocks.size)
        assertEquals(7, blocks.filter { it.list }.sumOf { it.text.lines().size })
        assertTrue(blocks.first().text.contains("100 天内处罚累计达到 3 分"))
        assertTrue(blocks.last().text.contains("联系管理员申请复核"))
        assertTrue(ForumCommunityRulesParser.parse(template.replace("3 分", "4 分")).first().text.contains("4 分"))
        assertFalse(blocks.joinToString { it.text }.contains("RouterLink"))
        val extended = ForumCommunityRulesParser.parse(template.replace("</section>", "<p>新增守则 &amp; 注意事项。</p></section>"))
        assertEquals(7, extended.size)
        assertEquals("新增守则 & 注意事项。", extended.last().text)
        assertTrue(runCatching { ForumCommunityRulesParser.parse(template.replace("</section>", "未标记的新条款</section>")) }.isFailure)
    }

    @Test fun onlyTrustedAssetPathsAndExactCommitIdsAreAccepted() {
        assertEquals("/assets/index-old.js", ForumCommunityRulesParser.entryPath(html("old")))
        assertEquals(sha, ForumCommunityRulesParser.commitSha(bundle(sha)))
        assertTrue(runCatching { ForumCommunityRulesParser.entryPath("""<script type="module" src="https://evil.test/assets/index-new.js"></script>""") }.isFailure)
        assertTrue(runCatching { ForumCommunityRulesParser.entryPath(html("old") + html("new")) }.isFailure)
        assertTrue(runCatching { ForumCommunityRulesParser.commitSha("commitSha:`main`") }.isFailure)
        assertTrue(runCatching { ForumCommunityRulesParser.commitSha(bundle(sha) + bundle(nextSha)) }.isFailure)
    }

    @Test fun checksDeploymentOnEntryAndCachesUntilTheDeployedBuildChanges() = runBlocking {
        MockWebServer().use { server ->
            val cache = MetadataCache(folder.newFolder()); val repo = repository(server, cache)
            assertEquals(bundledForumCommunityRules, repo.cached())
            server.enqueue(MockResponse().setBody(html("old")))
            server.enqueue(MockResponse().setBody(bundle(sha)))
            server.enqueue(MockResponse().setBody(template))
            val original = repo.refresh()
            assertEquals(sha, original.commitSha)
            assertEquals(original, repository(server, MetadataCache(folder.root.listFiles()!!.single())).cached())
            assertEquals("/rules", server.takeRequest().path)
            assertEquals("/assets/index-old.js", server.takeRequest().path)
            val sourceRead = server.takeRequest()
            assertEquals("/source/$sha/apps/web/src/views/community-rules/CommunityRulesView.vue", sourceRead.path)
            assertNull(sourceRead.getHeader("Authorization"))
            assertNull(sourceRead.getHeader("Cookie"))
            server.enqueue(MockResponse().setBody(html("old")))
            assertEquals(original, repo.refresh())
            assertEquals("/rules", server.takeRequest().path)
            assertEquals(4, server.requestCount)

            server.enqueue(MockResponse().setBody(html("new")))
            server.enqueue(MockResponse().setBody(bundle(nextSha)))
            server.enqueue(MockResponse().setBody(template.replace("3 分", "4 分")))
            val updated = repo.refresh()
            assertEquals(nextSha, updated.commitSha)
            assertTrue(updated.blocks.first().text.contains("4 分"))
            assertEquals(updated, repo.cached())
        }
    }

    @Test fun offlineAndUnrecognizedNewTemplatesKeepTheLastSuccessfulCopy() = runBlocking {
        MockWebServer().use { server ->
            val repo = repository(server, MetadataCache(folder.newFolder()))
            server.enqueue(MockResponse().setBody(html("old")))
            server.enqueue(MockResponse().setBody(bundle(sha)))
            server.enqueue(MockResponse().setBody(template))
            val original = repo.refresh()
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(runCatching { repo.refresh() }.isFailure)
            assertEquals(original, repo.cached())
            server.enqueue(MockResponse().setBody(html("new")))
            server.enqueue(MockResponse().setBody(bundle(nextSha)))
            server.enqueue(MockResponse().setBody(template.replace("<ul ", "<div ").replace("</ul>", "</div>")))
            assertTrue(runCatching { repo.refresh() }.isFailure)
            assertEquals(original, repo.cached())
        }
    }
}
