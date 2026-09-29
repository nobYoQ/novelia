package cc.novelia.app.data.network

import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** 长时间读取文件不能占满正文 API 的调度槽；仍复用连接池，限制同时传输的文件数量。 */
internal fun OkHttpClient.forDownloads(): OkHttpClient = newBuilder()
    .dispatcher(Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 })
    .followRedirects(true)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
