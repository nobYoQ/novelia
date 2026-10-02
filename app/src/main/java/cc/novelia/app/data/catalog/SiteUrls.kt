package cc.novelia.app.data.catalog

import java.net.URI

/** 原站迁移前后的内容地址共用入口；只接受已知域名，不按小说路径猜测外站身份。 */
internal object SiteUrls {
    private const val host = "n.novelia.cc"
    private val hosts = setOf(host, "books.fishhawk.top", "book.xkvi.top")

    fun isInternal(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase()
        return scheme in setOf("http", "https") && uri.host?.lowercase() in hosts && uri.userInfo == null &&
            (uri.port == -1 || uri.port == if (scheme == "https") 443 else 80)
    }

    fun normalize(uri: URI): URI {
        if (!isInternal(uri) || uri.host.equals(host, true)) return uri
        // 使用原始各部分，保留百分号编码、查询和锚点，避免二次转义或丢失定位信息。
        return URI(buildString {
            append("https://"); append(host); append(uri.rawPath)
            uri.rawQuery?.let { append('?'); append(it) }
            uri.rawFragment?.let { append('#'); append(it) }
        })
    }
}
