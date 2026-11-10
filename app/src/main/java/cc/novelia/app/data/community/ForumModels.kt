package cc.novelia.app.data.community

import kotlinx.serialization.Serializable
import java.time.Instant
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.model.User

/** 合约核对于 auto-novel/forum 部署 6bae949（2026-10-15，北京时间）。 */
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
    val activeAt: String, val tags: List<ForumTag> = emptyList(), val content: String = "",
    val authorBlocked: Boolean = false
) {
    fun article(categories: List<ForumCategory>, filterTagId: Long? = null) = Article(
        id = ForumLinks.localId(id), title = title, content = content,
        category = categories.firstOrNull { it.id == categoryId }?.title ?: "分类 $categoryId",
        locked = commentsLocked, pinned = pinOrder != null, hidden = status != 0,
        numViews = viewsCount, numComments = commentsCount, user = User(authorUsername),
        createAt = Instant.parse(createdAt).epochSecond, updateAt = Instant.parse(updatedAt).epochSecond,
        // 兼容旧部署的列表标签遗漏；新部署已返回完整标签。
        forumCategoryId = categoryId, forumTags = (tags + listOfNotNull(filterTagId?.let { tagId ->
            categories.firstOrNull { it.id == categoryId }?.tags?.firstOrNull { it.id == tagId }
        })).distinctBy { it.id }, forumAuthorId = authorId, forumFavorited = favorited,
        forumAuthorBlocked = authorBlocked
    )
}
@Serializable data class ForumComment(
    val id: Long, val postId: Long = 0, val subjectKey: String? = null, val rootId: Long? = null,
    val content: String, val authorId: Long, val authorUsername: String, val status: Int,
    val createdAt: String, val updatedAt: String,
    // 10 月 1 日起服务端始终返回计数；null 仅兼容旧响应，必须与明确的零回复区分。
    val replyCount: Long? = null,
    // 新版一级评论附带首屏回复；旧响应及独立回复接口可省略。
    val replies: ForumPage<ForumComment>? = null,
    val authorBlocked: Boolean = false
) {
    val replyRoot get() = rootId ?: id
    val createdEpoch get() = Instant.parse(createdAt).epochSecond
    fun visible(blockedUsers: Set<String>, hideUnavailable: Boolean) =
        !authorBlocked && authorUsername !in blockedUsers && (!hideUnavailable || status == 0)
    fun canModify(profile: Profile?, now: Long = Instant.now().epochSecond) =
        profile != null && (profile.role == "admin" || profile.userId == authorId && now - createdEpoch in 0 until ForumRules.MODIFICATION_SECONDS)
}
@Serializable data class ForumPostInput(val categoryId: Long, val title: String, val content: String, val tagIds: List<Long> = emptyList())
@Serializable data class ForumCommentInput(val content: String, val rootId: Long? = null)

enum class ForumSort(val apiValue: String, val label: String) {
    ACTIVE("active", "最近活跃"), UPDATED("updated", "最新更新"), NEWEST("newest", "最新发布"),
    VIEWS("views", "浏览最多"), COMMENTS("comments", "评论最多");

    // 保留 newest 的协议兼容；菜单和已保存的筛选状态只使用当前可见选项。
    val visibleSort get() = if(this == NEWEST) ACTIVE else this
    companion object { val visibleEntries = entries.filterNot { it == NEWEST } }
}

/** 接口一次返回完整列表（最多 1000 人），没有用户名字段。 */
@Serializable data class ForumBlacklistEntry(val userId: Long, val createdAt: String)

@Serializable data class ForumStrike(
    val id: Long, val reason: String, val evidence: String, val point: Int,
    val createdAt: String, val revokedAt: String? = null
) {
    val createdEpoch get() = Instant.parse(createdAt).epochSecond
    val revokedEpoch get() = revokedAt?.let { Instant.parse(it).epochSecond }
}

@Serializable data class ForumStrikePage(val total: Long, val items: List<ForumStrike>, val latestStrikeId: Long? = null) {
    fun pageCount() = ForumPage(total, items).pageCount()
}
@Serializable data class ForumStrikeReadState(val hasUnread: Boolean)
@Serializable data class ForumAttentionStatus(val strikes: ForumStrikeReadState)
