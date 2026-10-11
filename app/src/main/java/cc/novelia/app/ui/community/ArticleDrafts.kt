package cc.novelia.app.ui.community

import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.storage.appJson
import java.util.UUID
import kotlinx.serialization.encodeToString

internal data class ArticleDraft(val key: String, val title: String, val content: String, val category: String,
    val categoryId: Long? = null, val tagIds: List<Long>? = null, val categorySlug: String? = null) {
    val displayTitle: String get() = title.ifBlank { "未命名草稿" }

    // 分类 ID 会被重新分配；旧草稿只有数字 ID 时要求用户重新选择，不猜测其含义。
    fun forumCategory(categories: List<ForumCategory>) = categorySlug?.let { slug -> categories.firstOrNull { it.slug == slug } }
    fun forumTags(categories: List<ForumCategory>): List<Long> {
        val validIds = forumCategory(categories)?.tags?.map { it.id }.orEmpty().toSet()
        return tagIds.orEmpty().filter { it in validIds }.distinct()
    }
}

/** 旧固定键继续可见；新草稿用 UUID，永远不占用已有文章、评论或文库草稿的键。 */
internal object ArticleDrafts {
    fun newKey(forum: Boolean = false): String = "article:${if(forum) "forum-new" else "new"}:${UUID.randomUUID()}"
    private fun matches(key: String, prefix: String): Boolean = key == prefix ||
        (key.startsWith("$prefix:") && runCatching { UUID.fromString(key.removePrefix("$prefix:")) }.isSuccess)
    fun isForumNewPostKey(key: String): Boolean = matches(key, "article:forum-new")
    fun isNewPostKey(key: String): Boolean = matches(key, "article:new") || isForumNewPostKey(key)

    fun editedArticleId(key: String): String? = key.takeIf { it.startsWith("article:") }
        ?.removePrefix("article:")?.takeIf { it.isNotBlank() && ':' !in it && it !in setOf("new", "forum-new") }

    fun snapshot(title: String, content: String, category: String, categoryId: Long?, categorySlug: String?, tagIds: List<Long>): String =
        appJson.encodeToString(mapOf("title" to title, "content" to content, "category" to category,
            "categoryId" to categoryId.toString(), "categorySlug" to categorySlug.orEmpty(), "tagIds" to tagIds.joinToString(",")))

    fun read(key: String, snapshot: String): ArticleDraft {
        val fields = runCatching { appJson.decodeFromString<Map<String, String>>(snapshot) }.getOrNull()
        return ArticleDraft(key, fields?.get("title").orEmpty(), fields?.get("content") ?: snapshot,
            fields?.get("category")?.takeIf { it in categories } ?: "General",
            fields?.get("categoryId")?.toLongOrNull(),
            fields?.get("tagIds")?.split(',')?.mapNotNull { it.toLongOrNull() },
            fields?.get("categorySlug")?.takeIf { it.isNotBlank() })
    }

    fun posts(drafts: Map<String, String>): List<ArticleDraft> = drafts.entries
        .filter { isNewPostKey(it.key) || editedArticleId(it.key) != null }.map { read(it.key, it.value) }.reversed()
}
