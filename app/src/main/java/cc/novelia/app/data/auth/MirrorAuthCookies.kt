package cc.novelia.app.data.auth

import cc.novelia.app.data.network.BookSource
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 镜像 Cookie 独立序列化，再由 Session 用 Keystore 加密保存。 */
internal class MirrorAuthCookies(saved: String? = null) {
    private val origin = BookSource.XKVI.authOrigin.toHttpUrl()
    private val cookies = runCatching {
        saved?.let { appJson.decodeFromString<List<String>>(it) }?.mapNotNull { Cookie.parse(origin, it) }.orEmpty()
    }.getOrDefault(emptyList()).toMutableList()
    fun header(url: HttpUrl, now: Long = System.currentTimeMillis()): String = cookies
        .filter { it.expiresAt > now && it.matches(url) }.joinToString("; ") { "${it.name}=${it.value}" }
    fun accept(url: HttpUrl, headers: List<String>): String {
        headers.forEach { raw ->
            // 只将已知原认证站 Domain 映射到镜像，不接受外站 Cookie。
            val value = raw.replace(Regex("(?i);\\s*Domain=\\.?auth\\.novelia\\.cc(?=;|$)"), "")
                .replace(Regex("(?i);\\s*Domain=\\.?novelia\\.cc(?=;|$)"), "")
            val cookie = Cookie.parse(url, value) ?: return@forEach
            if(cookie.name == "accessToken" || cookie.domain != origin.host) return@forEach
            cookies.removeAll { it.name == cookie.name && it.path == cookie.path }
            if(cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
        }
        return appJson.encodeToString(cookies.map(Cookie::toString))
    }
}
