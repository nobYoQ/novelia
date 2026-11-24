package cc.novelia.app.data.network

import java.util.concurrent.TimeUnit
import cc.novelia.app.data.model.DEFAULT_DOWNLOAD_CONCURRENCY
import cc.novelia.app.data.model.DOWNLOAD_CONCURRENCY_RANGE
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient

internal class DownloadCallObserver(val onQueued: () -> Unit, val onDispatched: () -> Unit)

internal fun Dispatcher.setDownloadConcurrency(value: Int) {
    val limit = value.coerceIn(DOWNLOAD_CONCURRENCY_RANGE)
    maxRequests = limit
    maxRequestsPerHost = limit
}

/** 长时间读取文件不能占满正文 API 的调度槽；仍复用连接池，限制同时传输的文件数量。 */
internal fun OkHttpClient.forDownloads(): OkHttpClient = newBuilder()
    .dispatcher(Dispatcher().apply { setDownloadConcurrency(DEFAULT_DOWNLOAD_CONCURRENCY) })
    .echRedirects(true)
    .apply {
        // 应用拦截器在取得 OkHttp 名额后执行，放在最前面以覆盖原站和镜像。
        interceptors().add(0, Interceptor { chain ->
            val request = chain.request()
            request.tag(DownloadCallObserver::class.java)?.onDispatched?.invoke()
            chain.proceed(request)
        })
    }
    .readTimeout(120, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
