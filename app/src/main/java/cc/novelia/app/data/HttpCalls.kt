package cc.novelia.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpTextResponse(val code: Int, val text: String)

/** Reads and closes the body on OkHttp's dispatcher; cancellation also stops an in-flight body. */
internal suspend fun Call.awaitText(): HttpTextResponse = awaitBody {
    HttpTextResponse(it.code, if (it.isSuccessful) it.body?.string().orEmpty() else "")
}

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
