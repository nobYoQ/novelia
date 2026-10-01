package cc.novelia.app.data.markdown

import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SiteLink
import cc.novelia.app.data.catalog.SiteUrls
import java.net.URI

/** 原站 Markdown 链接可能使用根相对路径或省略协议的地址。 */
object MarkdownLinks {
    private val base = URI("https://n.novelia.cc/")
    fun resolve(destination: String, documentUrl: String? = null): String? = runCatching {
        val input = destination.trim()
        if (input.isEmpty()) return null
        val origin = documentUrl?.let { SiteUrls.normalize(URI(it)) } ?: base
        val uri = origin.resolve(if (input.startsWith("www.", true)) "https://$input" else input).normalize()
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        val normalized = SiteUrls.normalize(uri)
        normalized.toASCIIString().replaceBefore(':', normalized.scheme.lowercase())
    }.getOrNull()

    fun isInternal(url: String): Boolean = runCatching { SiteUrls.isInternal(URI(url)) }.getOrDefault(false)

    /** 返回 null 表示其他文档，空片段表示当前文档顶部。 */
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

    /** 存在原生页面时优先进入原生界面，其余站点页面使用应用内 WebView。 */
    fun nativeRoute(url: String): String? {
        val resolved = resolve(url) ?: return null
        // MarkdownText 会先处理当前文档内的标题跳转，再进入此路由；
        // 其他文档的标题和评论锚点保留完整 URL，交给 WebView。
        if (isInternal(resolved) && !URI(resolved).rawFragment.isNullOrEmpty()) return null
        when (val link = BookLinks.parse(resolved)) {
            is SiteLink.Book -> return if (link.chapterId == null) "book/${link.ref.provider}/${link.ref.id}"
                else "reader/${link.ref.provider}/${link.ref.id}/${link.chapterId}"
            is SiteLink.Post -> return "article/${link.id}"
            null -> Unit
        }
        if (!isInternal(resolved)) return null
        val uri = URI(resolved)
        // 站点列表页使用 WebView，保留 URL 中的筛选和分页参数。
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
