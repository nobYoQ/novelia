package cc.novelia.app.data.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/** 放在 ECH 之前；镜像直连，原站沿用 ECH。Cookie 只附在镜像 HTTPS 的标准端口。 */
internal class BookSourceInterceptor(private val sources: BookSources, private val redirects: Boolean = false) : Interceptor {
    fun withRedirects(value: Boolean) = BookSourceInterceptor(sources, value)
    override fun intercept(chain: Interceptor.Chain): Response {
        val selection = chain.request().tag(SourceSelection::class.java) ?: sources.capture()
        var request = prepare(chain.request(), selection)
        var hops = 0
        while(true) {
            sources.withSelection(selection) { }
            val received = chain.proceed(request)
            // 此镜像将部分非 2xx 响应包装为 502，却保留原站 Bearer challenge。
            // 仅凭明确的鉴权头恢复 401；普通网关故障不能触发写请求重放。
            val response = if(received.code == 502 && received.request.url.host == "book.xkvi.top" &&
                received.headers.values("WWW-Authenticate").any { it.trimStart().startsWith("Bearer ", true) || it.equals("Bearer", true) })
                received.newBuilder().code(401).message("Unauthorized").build() else received
            try { sources.withSelection(selection) { } } catch(error: Exception) { response.close(); throw error }
            val next = if(redirects) redirectRequest(response) else null
            if(next == null) return guardBody(response, selection)
            response.close()
            if(++hops > 10) throw IOException("重定向次数过多")
            // redirectRequest 已剥离跨域凭据；不会向其他来源重新添加镜像 Cookie。
            request = next
        }
    }

    internal fun prepare(request: Request, selection: SourceSelection): Request = sources.withSelection(selection) {
        val target = sources.route(request.url, selection)
        val builder = request.newBuilder().url(target).tag(SourceSelection::class.java, selection)
        if(target != request.url) builder.removeHeader("Host")
        if(request.header("Origin") in setOf(BookSource.ORIGINAL.origin, BookSource.ORIGINAL.authOrigin))
            builder.header("Origin", selection.source.origin)
        val mirror = target.isHttps && target.host == "book.xkvi.top" && target.port == 443 && target.username.isEmpty() && target.password.isEmpty()
        val originalAuth = selection.source == BookSource.ORIGINAL && target.isHttps && target.host == "auth.novelia.cc" && target.port == 443
        val cookies = request.headers.values("Cookie").flatMap { it.split(';') }
            .map(String::trim).filter { it.isNotEmpty() && (originalAuth || it.substringBefore('=').trim() != "accessToken") }.toMutableList()
        if(mirror) sources.cookie(selection)?.let(cookies::add)
        builder.removeHeader("Cookie")
        if(cookies.isNotEmpty()) builder.header("Cookie", cookies.joinToString("; "))
        builder.build()
    }

    /** 图片和流式下载也要在切换后停止读取旧响应。 */
    private fun guardBody(response: Response, selection: SourceSelection): Response {
        val body = response.body ?: return response
        val guarded = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                sources.withSelection(selection) { }
                val count = super.read(sink, byteCount)
                sources.withSelection(selection) { }
                return count
            }
        }.buffer()
        return response.newBuilder().body(object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = guarded
        }).build()
    }
}
