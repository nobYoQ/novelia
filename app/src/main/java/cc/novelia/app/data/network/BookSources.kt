package cc.novelia.app.data.network

import android.content.Context
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.auth.SessionChangedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class BookSource(val id: String, val title: String, val origin: String, val authOrigin: String, val forumOrigin: String) {
    ORIGINAL("original", "原站", "https://n.novelia.cc", "https://auth.novelia.cc", "https://forum.novelia.cc"),
    XKVI("xkvi", "XKVI 反代镜像", "https://book.xkvi.top", "https://book.xkvi.top", "https://book.xkvi.top"),
}

/** 镜像按路径分流；匹配完整路径段，避免把 postscript 等书站接口送到论坛。 */
internal fun isForumApiPath(path: String): Boolean = listOf(
    "/api/v1/category", "/api/v1/post", "/api/v1/comment", "/api/v1/external/comment",
    "/api/v1/me/post", "/api/v1/me/favorite",
).any { path == it || path.startsWith("$it/") }

internal fun isAuthApiPath(path: String): Boolean = path == "/api/v1/auth" || path.startsWith("/api/v1/auth/")

/** 可公开的选择状态，不含 Cookie 或任何令牌。revision 使切走再切回的旧请求也失效。 */
data class SourceSelection(val source: BookSource, val revision: Long = 0, val hasAccessToken: Boolean = false)

class BookSources internal constructor(
    initialSource: BookSource = BookSource.ORIGINAL,
    initialToken: String? = null,
    private val persist: (BookSource) -> Unit = {},
) {
    private val accessToken = initialToken
    private val mutable = MutableStateFlow(SourceSelection(
        if(initialSource == BookSource.XKVI && initialToken.isNullOrBlank()) BookSource.ORIGINAL else initialSource,
        hasAccessToken = !initialToken.isNullOrBlank(),
    ))
    val state = mutable.asStateFlow()
    private val listeners = mutableListOf<(SourceSelection) -> Unit>()

    @Synchronized fun capture(): SourceSelection = mutable.value
    @Synchronized internal fun <T> withCurrent(action: (SourceSelection) -> T): T = action(mutable.value)
    @Synchronized internal fun observe(listener: (SourceSelection) -> Unit) { listeners += listener; listener(mutable.value) }
    @Synchronized fun <T> withSelection(selection: SourceSelection, action: () -> T): T {
        if(selection != mutable.value) throw SessionChangedException()
        return action()
    }
    @Synchronized fun select(source: BookSource) {
        require(source != BookSource.XKVI || !accessToken.isNullOrEmpty()) { "当前安装包未配置镜像入口，请使用包含镜像配置的版本" }
        if(source == mutable.value.source) return
        persist(source)
        val next = SourceSelection(source, mutable.value.revision + 1, !accessToken.isNullOrEmpty())
        mutable.value = next
        listeners.forEach { it(next) }
    }
    @Synchronized internal fun cookie(selection: SourceSelection): String? = withSelection(selection) {
        if(selection.source == BookSource.XKVI) accessToken?.let { "accessToken=$it" } else null
    }

    /** 内容 URL 在本地仍采用原站地址；发出请求时才映射，保留路径、重复查询和编码。 */
    fun route(url: HttpUrl, selection: SourceSelection): HttpUrl {
        if(!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return url
        val origin = when(url.host) {
            "n.novelia.cc", "books.fishhawk.top" -> selection.source.origin
            "book.xkvi.top" -> when {
                isAuthApiPath(url.encodedPath) -> selection.source.authOrigin
                isForumApiPath(url.encodedPath) -> selection.source.forumOrigin
                else -> selection.source.origin
            }
            "forum.novelia.cc" -> if(isForumApiPath(url.encodedPath)) selection.source.forumOrigin else return url
            // 处罚等认证站接口未列入镜像路由，不能落入镜像的书站 API 兜底。
            "auth.novelia.cc" -> if(isAuthApiPath(url.encodedPath)) selection.source.authOrigin else return url
            else -> return url
        }.toHttpUrl()
        return url.newBuilder().scheme(origin.scheme).host(origin.host).port(origin.port).build()
    }

    companion object {
        fun load(context: Context): BookSources {
            val preferences = context.getSharedPreferences("book_sources", Context.MODE_PRIVATE)
            val token = BuildConfig.MIRROR_ACCESS_TOKEN.takeIf { it.isNotBlank() }
            val source = BookSource.entries.firstOrNull { it.id == preferences.getString("source", null) } ?: BookSource.ORIGINAL
            return BookSources(source, token) { selected ->
                check(preferences.edit().putString("source", selected.id).commit()) { "未能保存书源设置，请重试" }
            }
        }
    }
}
