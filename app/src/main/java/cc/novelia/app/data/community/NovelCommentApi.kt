package cc.novelia.app.data.community

import cc.novelia.app.data.auth.ApiSession
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.data.network.encodeSegment

/** 小说讨论托管在论坛，但读取和写入都使用小说账号（app=n）的会话。 */
class NovelCommentApi(private val http: NoveliaApi) {
    init {
        require(http.session == null || (http.session as? ApiSession)?.target == AuthTarget.NOVEL) {
            "小说评论必须使用小说登录会话"
        }
    }

    suspend fun comments(subjectKey: String, page: Int) =
        get<ForumPage<ForumComment>>(subjectPath(subjectKey), paging(page, 20))

    suspend fun replies(subjectKey: String, rootId: Long, page: Int) =
        get<ForumPage<ForumComment>>("${subjectPath(subjectKey)}/${positive(rootId)}/reply", paging(page, 20))

    suspend fun replyCount(subjectKey: String, rootId: Long): Long =
        get<ForumPage<ForumComment>>("${subjectPath(subjectKey)}/${positive(rootId)}/reply", paging(0, 1))
            .total.also { require(it >= 0) }

    suspend fun createComment(subjectKey: String, input: ForumCommentInput): ForumComment {
        validateContent(input.content)
        require(input.rootId == null || input.rootId > 0) { "回复根评论 ID 必须为正整数" }
        return decode(mutate("POST", subjectPath(subjectKey), appJson.encodeToString(input)))
    }

    suspend fun updateComment(id: Long, content: String): ForumComment {
        validateContent(content)
        return decode(mutate("PATCH", "external/comment/novel/${positive(id)}",
            appJson.encodeToString(mapOf("content" to content))))
    }

    suspend fun deleteComment(id: Long) { mutate("DELETE", "external/comment/novel/${positive(id)}") }

    private fun subjectPath(subjectKey: String): String {
        require(subjectKey.isNotBlank() && ForumRules.length(subjectKey) <= 255) { "小说讨论标识无效" }
        return "external/comment/novel/${encodeSegment(subjectKey)}"
    }
    private fun positive(id: Long): Long { require(id > 0); return id }
    private fun validateContent(content: String) {
        ForumRules.contentError(content, comment = true)?.let { throw IllegalArgumentException(it) }
    }
    private fun paging(page: Int, size: Int): Map<String, String> {
        require(page in 0 until Int.MAX_VALUE)
        return mapOf("page" to (page + 1).toString(), "page_size" to size.toString())
    }
    private suspend inline fun <reified T> decode(raw: String): T = withContext(Dispatchers.Default) {
        appJson.decodeFromString(raw)
    }
    private suspend inline fun <reified T> get(path: String, params: Map<String, String>): T =
        decode(http.request("GET", path, params = params, errorMessage = ::forumErrorMessage))
    private suspend fun mutate(method: String, path: String, body: String? = null) =
        http.request(method, path, body, errorMessage = ::forumErrorMessage)
}
