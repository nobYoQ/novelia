package cc.novelia.app.data.catalog

/** A namespace prevents numeric forum IDs colliding with old saved articles and drafts. */
object ForumLinks {
    const val ORIGIN = "https://forum.novelia.cc"
    fun localId(id: Long): String { require(id > 0); return "f-$id" }
    fun postId(id: String): Long? = id.takeIf { it.startsWith("f-") }?.removePrefix("f-")?.toLongOrNull()?.takeIf { it > 0 }
    fun articleUrl(id: String) = postId(id)?.let { "$ORIGIN/p/$it" } ?: "https://n.novelia.cc/forum/$id"
}
