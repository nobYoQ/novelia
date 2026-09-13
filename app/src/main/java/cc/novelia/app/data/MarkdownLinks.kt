package cc.novelia.app.data

import java.net.URI

/** Markdown destinations may be root-relative or protocol-relative on the original site. */
object MarkdownLinks {
    private val base = URI("https://n.novelia.cc/")
    fun resolve(destination: String, documentUrl: String? = null): String? = runCatching {
        val input = destination.trim()
        if (input.isEmpty()) return null
        val origin = documentUrl?.let { URI(it) } ?: base
        val uri = origin.resolve(if (input.startsWith("www.", true)) "https://$input" else input).normalize()
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        uri.toASCIIString().replaceBefore(':', uri.scheme.lowercase())
    }.getOrNull()

    fun isInternal(url: String): Boolean = runCatching { URI(url).host.equals(base.host, true) }.getOrDefault(false)

    /** null means another document; an empty fragment denotes the top of this document. */
    fun localFragment(destination: String, documentUrl: String?): String? = runCatching {
        val input = destination.trim()
        if (input.startsWith('#')) return URI(input).fragment
        if (documentUrl == null) return null
        val current = URI(resolve(documentUrl) ?: return null)
        val target = URI(resolve(input, documentUrl) ?: return null)
        if (!current.scheme.equals(target.scheme, true) || !current.authority.equals(target.authority, true) ||
            current.path.trimEnd('/') != target.path.trimEnd('/') || current.rawQuery != target.rawQuery) return null
        target.fragment
    }.getOrNull()

    fun commentDocumentUrl(site: String): String? = when {
        site.startsWith("article-") -> "${base}forum/${site.removePrefix("article-")}"
        site.startsWith("wenku-") -> "${base}wenku/${site.removePrefix("wenku-")}"
        site.startsWith("web-") -> site.removePrefix("web-").split('-', limit = 2).takeIf { it.size == 2 }
            ?.let { "${base}novel/${it[0]}/${it[1]}" }
        else -> null
    }

    /** Screens with a native equivalent. Other site pages stay in an in-app WebView. */
    fun nativeRoute(url: String): String? {
        // MarkdownText handles headings within its current document before routing here.
        // Other documents' heading/comment anchors keep their full URL in WebView.
        if (isInternal(url) && !URI(url).rawFragment.isNullOrEmpty()) return null
        when (val link = BookLinks.parse(url)) {
            is SiteLink.Book -> return if (link.chapterId == null) "book/${link.ref.provider}/${link.ref.id}"
                else "reader/${link.ref.provider}/${link.ref.id}/${link.chapterId}"
            is SiteLink.Post -> return "article/${link.id}"
            null -> Unit
        }
        if (!isInternal(url)) return null
        val uri = URI(url)
        // Preserve filters and pagination on site list pages through the WebView.
        if (uri.rawQuery != null) return null
        return when (uri.path.trimEnd('/')) {
            "/forum" -> "community"
            "/setting" -> "settings"
            "/read-history" -> "history"
            "/novel", "/novel-list" -> "discover"
            else -> null
        }
    }
}
