package cc.novelia.app.data.network

import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.model.ForumCommunityRules
import cc.novelia.app.data.model.ForumCommunityRulesParser
import cc.novelia.app.data.model.bundledForumCommunityRules
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** 使用实际部署入口的构建 SHA，避免把仓库尚未部署的 main 当作线上守则。 */
class ForumCommunityRulesRepository(
    private val cache: MetadataCache,
    private val site: NoveliaApi = NoveliaApi(null, "https://forum.novelia.cc/"),
    private val source: NoveliaApi = NoveliaApi(null, SOURCE_BASE)
) {
    init { require(site.session == null && source.session == null) { "公开守则不使用登录会话" } }
    private val mutex = Mutex()
    private val key = hashName("forum-community-rules-v1")

    suspend fun cached(): ForumCommunityRules = withContext(Dispatchers.IO) {
        runCatching { cache.read(key)?.let { appJson.decodeFromString<ForumCommunityRules>(it) } }
            .getOrNull()?.takeIf { it.blocks.isNotEmpty() && it.commitSha.matches(Regex("[a-f0-9]{40}")) }
            ?: bundledForumCommunityRules
    }

    suspend fun refresh(): ForumCommunityRules = mutex.withLock {
        val entryPath = withContext(Dispatchers.Default) {
            ForumCommunityRulesParser.entryPath(site.request("GET", "rules"))
        }
        val previous = cached()
        // 同一部署入口无需重复下载脚本和源码；每次进入仍核对线上入口是否变化。
        if(previous.entryPath == entryPath) return@withLock previous
        val sha = withContext(Dispatchers.Default) {
            ForumCommunityRulesParser.commitSha(site.request("GET", entryPath))
        }
        val raw = source.request("GET", "$sha/apps/web/src/views/community-rules/CommunityRulesView.vue")
        val document = withContext(Dispatchers.Default) {
            ForumCommunityRules(ForumCommunityRulesParser.parse(raw), entryPath, sha)
        }
        // 全部读取、校验成功后才替换；错误、断网或取消保留此前可用的缓存。
        withContext(Dispatchers.IO) { cache.write(key, appJson.encodeToString(document)) }
        document
    }

    companion object { const val SOURCE_BASE = "https://raw.githubusercontent.com/auto-novel/forum/" }
}
