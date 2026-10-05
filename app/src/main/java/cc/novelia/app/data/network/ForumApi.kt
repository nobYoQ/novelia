package cc.novelia.app.data.network

import cc.novelia.app.data.auth.ApiSession
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString

class ForumApi(private val http: NoveliaApi) {
    init { require(http.session == null || (http.session as? ApiSession)?.target == AuthTarget.FORUM) { "论坛必须使用独立登录会话" } }
    suspend fun categories() = http.get<List<ForumCategory>>("category/")
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
    suspend fun createPost(input: ForumPostInput) = appJson.decodeFromString<ForumPost>(http.post("post/", input))
    suspend fun updatePost(id: Long, input: ForumPostInput) = appJson.decodeFromString<ForumPost>(http.request("PATCH", postPath(id), appJson.encodeToString(input)))
    suspend fun deletePost(id: Long) { http.request("DELETE", postPath(id)) }
    suspend fun favorites(page: Int) = http.get<ForumPage<ForumPost>>("me/favorite", paging(page, 20))
    suspend fun myPosts(page: Int) = http.get<ForumPage<ForumPost>>("me/post", paging(page, 20))
    suspend fun favorite(id: Long, value: Boolean) { http.request(if(value) "PUT" else "DELETE", "${postPath(id)}favorite") }
    suspend fun comments(id: Long, page: Int) = http.get<ForumPage<ForumComment>>("${postPath(id)}comment", paging(page, 20))
    suspend fun createComment(id: Long, input: ForumCommentInput) = appJson.decodeFromString<ForumComment>(http.post("${postPath(id)}comment", input))
    suspend fun updateComment(id: Long, content: String) = appJson.decodeFromString<ForumComment>(http.request("PATCH", "comment/${positive(id)}", appJson.encodeToString(mapOf("content" to content))))
    suspend fun deleteComment(id: Long) { http.request("DELETE", "comment/${positive(id)}") }
    // External novel comments exist on the preview server. Keep the subject key explicit until the main site migrates.
    suspend fun externalComments(subjectKey: String, page: Int) = http.get<ForumPage<ForumComment>>("external/comment/novel/${encodeSegment(subjectKey)}", paging(page, 20))
    private fun postPath(id: Long) = "post/${positive(id)}/"
    private fun positive(id: Long): Long { require(id > 0); return id }
    companion object { const val BASE_URL = "https://forum.novelia.cc/api/v1/" }
}
