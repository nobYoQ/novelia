package cc.novelia.app.data.network

import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.Serializable

/** Anonymous transport probes; independent of the future forum UI, models and account session. */
internal object EchForumProbe {
    const val CATEGORIES_URL = "https://forum.novelia.cc/api/v1/category/"
    const val POSTS_URL = "https://forum.novelia.cc/api/v1/post/?page=1&page_size=20&category=announcements&q=&sort=active"

    fun categoryCount(json: String): Int = appJson.decodeFromString<List<Category>>(json).size
    fun postCount(json: String): Int = appJson.decodeFromString<Posts>(json).let {
        require(it.total >= 0)
        it.items.size
    }

    @Serializable private data class Category(val id: Long, val slug: String)
    @Serializable private data class Post(val id: Long, val title: String)
    @Serializable private data class Posts(val total: Long, val items: List<Post>)
}
