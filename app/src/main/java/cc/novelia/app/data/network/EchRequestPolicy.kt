package cc.novelia.app.data.network

/**
 * 0 disables that phase's deadline, matching OkHttp's timeout configuration.
 * connect: one DNS + TCP + TLS attempt (at most three for bodyless GET/HEAD).
 * write: the complete request-send phase, including upload production.
 * read: response headers after sending, then each individual streamed read.
 * The interceptor separately keeps callTimeout active until EOF or body.close().
 */
internal data class EchTimeouts(
    val connectMillis: Long,
    val readMillis: Long,
    val writeMillis: Long
) {
    init {
        require(listOf(connectMillis, readMillis, writeMillis).all { it in 0..Int.MAX_VALUE.toLong() })
    }
}
