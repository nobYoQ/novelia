package cc.novelia.app.data.model

import cc.novelia.app.data.catalog.ForumLinks
import kotlinx.serialization.Serializable
import java.time.Instant

/** 合约核对于 auto-novel/forum 部署 ae80f73（2026-10-01）。 */
@Serializable data class ForumPage<T>(val total: Long, val items: List<T>) {
    fun pageCount(pageSize: Int = 20): Int {
        require(pageSize > 0 && total >= 0)
        return (total / pageSize + if (total % pageSize == 0L) 0 else 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
@Serializable data class ForumTag(val id: Long, val name: String, val color: Int = 0, val sortOrder: Int = 0)
@Serializable data class ForumCategory(val id: Long, val slug: String, val tags: List<ForumTag> = emptyList()) {
    val title get() = when(slug) { "novel" -> "小说讨论"; "announcements" -> "站务公告"; "guide" -> "使用指南"; "feedback" -> "意见反馈"; else -> slug }
    val displayOrder get() = when(slug) { "announcements" -> 0; "novel" -> 1; "feedback" -> 2; else -> 3 }
}
@Serializable data class ForumPost(
    val id: Long, val categoryId: Long, val title: String, val authorId: Long, val authorUsername: String,
    val status: Int, val viewsCount: Int, val commentsCount: Int, val commentsLocked: Boolean,
    val pinOrder: Int? = null, val favorited: Boolean = false, val createdAt: String, val updatedAt: String,
    val activeAt: String, val tags: List<ForumTag> = emptyList(), val content: String = ""
) {
    fun article(categories: List<ForumCategory>) = Article(
        id = ForumLinks.localId(id), title = title, content = content,
        category = categories.firstOrNull { it.id == categoryId }?.title ?: "分类 $categoryId",
        locked = commentsLocked, pinned = pinOrder != null, hidden = status != 0,
        numViews = viewsCount, numComments = commentsCount, user = User(authorUsername),
        createAt = Instant.parse(createdAt).epochSecond, updateAt = Instant.parse(updatedAt).epochSecond,
        forumCategoryId = categoryId, forumTags = tags, forumAuthorId = authorId, forumFavorited = favorited
    )
}
@Serializable data class ForumComment(
    val id: Long, val postId: Long = 0, val subjectKey: String? = null, val rootId: Long? = null,
    val content: String, val authorId: Long, val authorUsername: String, val status: Int,
    val createdAt: String, val updatedAt: String,
    // 10 月 1 日起服务端始终返回计数；null 仅兼容旧响应，必须与明确的零回复区分。
    val replyCount: Long? = null
) {
    val replyRoot get() = rootId ?: id
    val createdEpoch get() = Instant.parse(createdAt).epochSecond
    fun canModify(profile: Profile?, now: Long = Instant.now().epochSecond) =
        profile != null && (profile.role == "admin" || profile.userId == authorId && now - createdEpoch in 0 until ForumRules.MODIFICATION_SECONDS)
}
@Serializable data class ForumPostInput(val categoryId: Long, val title: String, val content: String, val tagIds: List<Long> = emptyList())
@Serializable data class ForumCommentInput(val content: String, val rootId: Long? = null)

enum class ForumSort(val apiValue: String, val label: String) {
    ACTIVE("active", "最近活跃"), NEWEST("newest", "最新发布"),
    VIEWS("views", "浏览最多"), COMMENTS("comments", "评论最多")
}

@Serializable data class ForumStrike(
    val id: Long, val reason: String, val evidence: String, val point: Int,
    val createdAt: String, val revokedAt: String? = null
) {
    val createdEpoch get() = Instant.parse(createdAt).epochSecond
    val revokedEpoch get() = revokedAt?.let { Instant.parse(it).epochSecond }
}
