package cc.novelia.app.data.network

import cc.novelia.app.data.auth.ApiSession
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import okhttp3.Response

class ForumApi(private val http: NoveliaApi) {
    init { require(http.session == null || (http.session as? ApiSession)?.target == AuthTarget.FORUM) { "论坛必须使用独立登录会话" } }
    suspend fun categories() = http.get<List<ForumCategory>>("category/").sortedBy { it.displayOrder }
    private fun paging(page: Int, pageSize: Int): Map<String, String> {
        require(page >= 0 && page < Int.MAX_VALUE && pageSize in 1..100)
        return mapOf("page" to (page + 1).toString(), "page_size" to pageSize.toString())
    }
    suspend fun posts(page: Int, category: String, query: String = "", sort: String = "active", tagIds: List<Long> = emptyList(), pageSize: Int = 20): ForumPage<ForumPost> {
        require(sort in setOf("active", "newest", "views", "comments"))
        return http.get("post/", paging(page, pageSize) + buildMap {
            put("category", category); put("q", query); put("sort", sort)
            if (tagIds.isNotEmpty()) put("tag", tagIds.distinct().joinToString(","))
        })
    }
    suspend fun post(id: Long) = http.get<ForumPost>(postPath(id))
    suspend fun createPost(input: ForumPostInput): ForumPost {
        requireValidPost(input)
        return appJson.decodeFromString(mutate("POST", "post/", appJson.encodeToString(input)))
    }
    suspend fun updatePost(id: Long, input: ForumPostInput): ForumPost {
        requireValidPost(input)
        return appJson.decodeFromString(mutate("PATCH", postPath(id), appJson.encodeToString(input)))
    }
    suspend fun deletePost(id: Long) { mutate("DELETE", postPath(id)) }
    suspend fun favorites(page: Int) = http.get<ForumPage<ForumPost>>("me/favorite", paging(page, 20))
    suspend fun myPosts(page: Int) = http.get<ForumPage<ForumPost>>("me/post", paging(page, 20))
    suspend fun favorite(id: Long, value: Boolean) { mutate(if(value) "PUT" else "DELETE", "${postPath(id)}favorite") }
    suspend fun comments(id: Long, page: Int) = http.get<ForumPage<ForumComment>>("${postPath(id)}comment", paging(page, 20))
    suspend fun replies(id: Long, rootId: Long, page: Int) =
        http.get<ForumPage<ForumComment>>("${postPath(id)}comment/${positive(rootId)}/reply", paging(page, 20))
    // 线上一级评论经常省略 replyCount；只取一项，使用独立回复接口的 total 补齐计数。
    suspend fun replyCount(id: Long, rootId: Long): Long =
        http.get<ForumPage<ForumComment>>("${postPath(id)}comment/${positive(rootId)}/reply", paging(0, 1)).total.also { require(it >= 0) }
    suspend fun createComment(id: Long, input: ForumCommentInput): ForumComment {
        requireValidComment(input.content)
        require(input.rootId == null || input.rootId > 0) { "回复根评论 ID 必须为正整数" }
        return appJson.decodeFromString(mutate("POST", "${postPath(id)}comment", appJson.encodeToString(input)))
    }
    suspend fun updateComment(id: Long, content: String): ForumComment {
        requireValidComment(content)
        // PATCH rejects rootId, including an explicit null.
        return appJson.decodeFromString(mutate("PATCH", "comment/${positive(id)}", appJson.encodeToString(mapOf("content" to content))))
    }
    suspend fun deleteComment(id: Long) { mutate("DELETE", "comment/${positive(id)}") }
    // External novel comments exist on the preview server. Keep the subject key explicit until the main site migrates.
    suspend fun externalComments(subjectKey: String, page: Int) = http.get<ForumPage<ForumComment>>("external/comment/novel/${encodeSegment(subjectKey)}", paging(page, 20))
    suspend fun externalReplies(subjectKey: String, rootId: Long, page: Int) =
        http.get<ForumPage<ForumComment>>("external/comment/novel/${encodeSegment(subjectKey)}/${positive(rootId)}/reply", paging(page, 20))
    private fun postPath(id: Long) = "post/${positive(id)}/"
    private fun positive(id: Long): Long { require(id > 0); return id }
    private fun requireValidPost(input: ForumPostInput) { ForumRules.postError(input)?.let { throw IllegalArgumentException(it) } }
    private fun requireValidComment(content: String) { ForumRules.contentError(content, comment = true)?.let { throw IllegalArgumentException(it) } }
    private suspend fun mutate(method: String, path: String, body: String? = null) =
        http.request(method, path, body, errorMessage = ::forumErrorMessage)
    companion object { const val BASE_URL = "https://forum.novelia.cc/api/v1/" }
}

/** The forum returns plain-text validation/permission errors, including domain filtering. */
private fun forumErrorMessage(response: Response): String? {
    if (response.code !in setOf(400, 403) || response.body?.contentType()?.let { it.type == "text" && it.subtype == "plain" } != true) return null
    val message = response.peekBody(2048).string().trim()
    if(message.isBlank() || message.length > 300 || '<' in message || '>' in message) return null
    val contentLabel = if("comment" in response.request.url.pathSegments) "评论" else "正文"
    return when {
        message.startsWith("title ") -> "标题" + message.removePrefix("title ")
        message.startsWith("content ") -> contentLabel + message.removePrefix("content ")
        else -> message
    }
}
