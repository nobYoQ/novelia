package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class Article(
    val id: String = "", val title: String = "", val content: String = "", val category: String = "General",
    val locked: Boolean = false, val pinned: Boolean = false, val hidden: Boolean = false, val numViews: Int = 0, val numComments: Int = 0,
    val user: User = User(), val createAt: Long = 0, val updateAt: Long = 0
)

@Serializable data class Comment(
    val id: String = "", val user: User = User(), val content: String = "", val hidden: Boolean = false,
    val createAt: Long = 0, val numReplies: Int = 0, val replies: List<Comment> = emptyList()
)
