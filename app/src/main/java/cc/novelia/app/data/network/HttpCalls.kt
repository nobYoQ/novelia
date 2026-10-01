package cc.novelia.app.data.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal data class HttpTextResponse(val code: Int, val text: String)

/** 在 OkHttp 调度线程中读取并关闭响应体，取消请求也会中止正在读取的响应体。 */
internal suspend fun Call.awaitText(): HttpTextResponse = awaitBody {
    HttpTextResponse(it.code, if (it.isSuccessful) it.body?.string().orEmpty() else "")
}

/**
 * 将 OkHttp 回调桥接为可取消的挂起调用。取消协程会取消底层 Call，包括正在读取的响应体。
 * 回调在 OkHttp 调度线程消费并关闭 Response；即使取消与响应同时到达，也要关闭响应，
 * 只有仍活跃的 continuation 才接收结果或异常。
 */
internal suspend fun <T> Call.awaitBody(readResponse: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            try {
                val result = response.use(readResponse)
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
