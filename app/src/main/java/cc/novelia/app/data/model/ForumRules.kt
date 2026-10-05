package cc.novelia.app.data.model

import java.time.Instant

/** Rules of the deployed forum (6c65702), shared by forms and API validation. */
object ForumRules {
    const val TITLE_LIMIT = 100
    const val POST_LIMIT = 20000
    const val COMMENT_LIMIT = 1000
    const val TAG_LIMIT = 3
    const val ANNOUNCEMENTS_ID = 2L
    const val MODIFICATION_SECONDS = 20 * 60L

    // Go counts Unicode code points, not UTF-16 units (an emoji can use two units).
    fun length(text: String) = text.codePointCount(0, text.length)
    fun titleError(title: String): String? = when {
        length(title.trim()) < 2 -> "标题至少需要 2 字"
        length(title.trim()) > TITLE_LIMIT -> "标题不能超过 $TITLE_LIMIT 字"
        else -> null
    }
    fun contentError(content: String, comment: Boolean = false): String? {
        val label = if (comment) "评论" else "正文"
        val limit = if (comment) COMMENT_LIMIT else POST_LIMIT
        return when {
            content.isBlank() -> "请输入$label"
            length(content) > limit -> "${label}不能超过 $limit 字"
            else -> null
        }
    }
    fun postError(input: ForumPostInput): String? = when {
        input.categoryId <= 0 -> "请选择帖子分类"
        titleError(input.title) != null -> titleError(input.title)
        contentError(input.content) != null -> contentError(input.content)
        input.tagIds.size > TAG_LIMIT -> "一个帖子最多只能添加 $TAG_LIMIT 个标签"
        input.tagIds.any { it <= 0 } || input.tagIds.distinct().size != input.tagIds.size -> "请选择有效且不重复的标签"
        else -> null
    }
    fun canSelectCategory(categoryId: Long?, profile: Profile?) =
        categoryId != null && (categoryId != ANNOUNCEMENTS_ID || profile?.role == "admin")

    fun canWrite(profile: Profile?) = profile?.role in setOf("admin", "trusted", "member")

    fun canPublish(categoryId: Long?, profile: Profile?) =
        canWrite(profile) && canSelectCategory(categoryId, profile)

    fun canEditPost(article: Article, profile: Profile?) =
        profile != null && canWrite(profile) && (profile.role == "admin" || profile.userId != null && profile.userId == article.forumAuthorId)

    fun canDeletePost(article: Article, profile: Profile?, now: Long = Instant.now().epochSecond) =
        profile != null && (profile.role == "admin" || profile.userId != null && profile.userId == article.forumAuthorId &&
            now - article.createAt in 0 until MODIFICATION_SECONDS)
}
