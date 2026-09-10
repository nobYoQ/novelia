package cc.novelia.app.data

import java.net.URI

sealed interface SiteLink {
    data class Book(val ref: BookRef, val chapterId: String? = null) : SiteLink
    data class Post(val id: String) : SiteLink
}
object BookLinks {
    fun parse(input: String): SiteLink? = runCatching {
        val url = Regex("https?://[^\\s<>]+").find(input.trim())?.value ?: return null
        val uri = URI(url); if (uri.scheme !in listOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.trim('/').split('/').filter { it.isNotEmpty() }
        fun book(provider: String, id: String?, chapter: String? = null): SiteLink? = id?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) }?.let { SiteLink.Book(BookRef(provider, it), chapter?.takeIf { c -> c.matches(Regex("[a-zA-Z0-9_-]+")) }) }
        when (host) {
            "n.novelia.cc" -> when(path.firstOrNull()) {
                "novel" -> if(path.getOrNull(1) in providers) book(path[1], path.getOrNull(2), path.getOrNull(3)) else null
                "wenku" -> book("wenku", path.getOrNull(1))
                "forum" -> path.getOrNull(1)?.takeIf { it.matches(Regex("[a-zA-Z0-9]+")) }?.let { SiteLink.Post(it) }
                else -> null
            }
            "kakuyomu.jp" -> if(path.firstOrNull() == "works") book("kakuyomu", path.getOrNull(1)) else null
            "ncode.syosetu.com", "novel18.syosetu.com" -> book("syosetu", path.firstOrNull()?.lowercase(), path.getOrNull(1))
            "novelup.plus" -> if(path.firstOrNull() == "story") book("novelup", path.getOrNull(1)) else null
            "syosetu.org" -> if(path.firstOrNull() == "novel") book("hameln", path.getOrNull(1)) else null
            "www.pixiv.net", "pixiv.net" -> when {
                path.take(2) == listOf("novel", "series") -> book("pixiv", path.getOrNull(2))
                path.firstOrNull() == "novel" && path.getOrNull(1) == "show.php" -> uri.query?.split('&')?.firstOrNull { it.startsWith("id=") }?.substringAfter('=')?.let { book("pixiv", "s$it") }
                else -> null
            }
            "www.alphapolis.co.jp", "alphapolis.co.jp" -> if(path.firstOrNull() == "novel" && path.size >= 3) book("alphapolis", "${path[1]}-${path[2]}") else null
            else -> null
        }
    }.getOrNull()
    fun source(ref: BookRef): String? = when(ref.provider) {
        "syosetu" -> "https://ncode.syosetu.com/${ref.id}/"
        "kakuyomu" -> "https://kakuyomu.jp/works/${ref.id}"
        "novelup" -> "https://novelup.plus/story/${ref.id}"
        "hameln" -> "https://syosetu.org/novel/${ref.id}/"
        "pixiv" -> if(ref.id.startsWith('s')) "https://www.pixiv.net/novel/show.php?id=${ref.id.drop(1)}" else "https://www.pixiv.net/novel/series/${ref.id}"
        "alphapolis" -> "https://www.alphapolis.co.jp/novel/${ref.id.replace('-', '/')}"
        else -> null
    }
}
