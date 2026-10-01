package cc.novelia.app.data.catalog

import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.storage.hashName
import java.net.URI
import java.net.URLEncoder

internal data class ClipboardSiteLink(val url: String, val route: String, val label: String)

/**
 * 有界扫描剪贴板文本，只识别原站支持的内容页面，拒绝接口、认证页、外站及歧义路径。
 * 无查询或锚点时可进入原生路由；带这些上下文时保留完整地址，由站内 WebView 承接。
 * 此处只生成跳转建议，是否读取剪贴板及何时提示由导航层生命周期处理。
 */
internal object ClipboardLinks {
    private val urls = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val id = Regex("[a-zA-Z0-9_-]+")
    private val postId = Regex("[a-zA-Z0-9]+")

    fun find(text: String?): ClipboardSiteLink? = urls.findAll(text.orEmpty().take(16_384)).take(8)
        .mapNotNull { match -> parse(match.value.trimEnd('。', '，', '；', '！', '？', '）', '】', '》', ')', ']', '}', '」', '』', ',', '.', '!')) }.firstOrNull()

    private fun parse(url: String): ClipboardSiteLink? = runCatching {
        if(url.length > 2_048) return null
        val uri = URI(url)
        val scheme = uri.scheme.lowercase()
        if(scheme !in setOf("http", "https") || !uri.host.equals("n.novelia.cc", true) || uri.userInfo != null) return null
        if(uri.port != -1 && uri.port != if(scheme == "https") 443 else 80) return null
        if(uri.rawPath.contains("%2f", true) || uri.rawPath.contains("%5c", true)) return null
        val parts = uri.path.trim('/').split('/')
        val label = when {
            parts.size in 3..4 && parts[0] == "novel" && parts[1] in providers && id.matches(parts[2]) &&
                (parts.size == 3 || id.matches(parts[3])) -> if(parts.size == 4) "阅读章节" else "小说详情"
            parts.size == 2 && parts[0] == "wenku" && id.matches(parts[1]) -> "文库详情"
            parts.size == 2 && parts[0] == "forum" && postId.matches(parts[1]) -> "论坛文章"
            parts == listOf("forum") -> "社区"
            parts == listOf("setting") -> "设置"
            parts == listOf("read-history") -> "阅读历史"
            parts == listOf("novel") || parts == listOf("novel-list") -> "发现"
            else -> return null
        }
        val normalized = URI(buildString {
            append("https://n.novelia.cc"); append(uri.rawPath)
            uri.rawQuery?.let { append('?'); append(it) }
            uri.rawFragment?.let { append('#'); append(it) }
        }).toASCIIString()
        // 查询条件和评论锚点由站内网页承接，不能在提醒跳转时静默丢掉。
        val route = if(uri.rawQuery != null || !uri.rawFragment.isNullOrEmpty())
            "web?url=${URLEncoder.encode(normalized, "UTF-8")}" else MarkdownLinks.nativeRoute(normalized) ?: return null
        ClipboardSiteLink(normalized, route, label)
    }.getOrNull()
}

/** 仅在进程内保存少量链接摘要去重，不持久化剪贴板原文或向网络发送剪贴板内容。 */
internal class ClipboardLinkHistory {
    private val seen = LinkedHashSet<String>()
    fun next(text: String?): ClipboardSiteLink? {
        val link = ClipboardLinks.find(text) ?: return null
        if(!seen.add(hashName(link.url))) return null
        if(seen.size > 16) seen.remove(seen.first())
        return link
    }
}
