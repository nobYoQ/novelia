package cc.novelia.app.data.community

import kotlinx.serialization.Serializable
import cc.novelia.app.data.model.User

@Serializable data class Article(
    val id: String = "", val title: String = "", val content: String = "", val category: String = "General",
    val locked: Boolean = false, val pinned: Boolean = false, val hidden: Boolean = false, val numViews: Int = 0, val numComments: Int = 0,
    val user: User = User(), val createAt: Long = 0, val updateAt: Long = 0,
    val forumCategoryId: Long? = null, val forumTags: List<ForumTag> = emptyList(),
    val forumAuthorId: Long? = null, val forumFavorited: Boolean = false
)

@Serializable data class Comment(
    val id: String = "", val user: User = User(), val content: String = "", val hidden: Boolean = false,
    val createAt: Long = 0, val numReplies: Int = 0, val replies: List<Comment> = emptyList()
)
