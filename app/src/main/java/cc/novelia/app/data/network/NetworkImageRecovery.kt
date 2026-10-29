package cc.novelia.app.data.network

import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import java.io.IOException

internal fun isRemoteImage(data: Any?) = data.toString().let { it.startsWith("https://") || it.startsWith("http://") }

/** Coil consumes the entire image before publishing it, so replacing a failed read is safe. */
internal class NetworkImageRecovery(private val connections: NetworkConnections) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (!isRemoteImage(chain.request.data)) return chain.proceed(chain.request)
        return try {
            withRequestBudget(20_000) {
                recoverRead(connections) {
                    chain.proceed(chain.request).also { result ->
                        if (result is ErrorResult && result.throwable.isRecoverableNetworkFailure()) throw result.throwable
                    }
                }
            }
        } catch (error: IOException) { ErrorResult(null, chain.request, error) }
    }
}
