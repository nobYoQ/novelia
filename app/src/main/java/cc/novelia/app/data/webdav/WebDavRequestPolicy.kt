package cc.novelia.app.data.webdav

import java.security.MessageDigest

/** 所有自动入口在 manager 的互斥锁内共享节流；连续编辑不会重置计时。 */
internal class WebDavAutomaticPolicy(private val nowMillis: () -> Long) {
    private var generation: Long? = null
    private var lastAttempt: Long? = null
    private var lastFullCheck: Long? = null

    fun domains(binding: WebDavConfig, pending: Set<SyncDomain>): Set<SyncDomain> {
        selectBinding(binding)
        val now = nowMillis()
        if(lastAttempt?.let { now - it < 120_000L } == true) return emptySet()
        val fullCheck = lastFullCheck?.let { now - it >= 180_000L } ?: true
        val domains = if(fullCheck) binding.selected else pending.intersect(binding.selected)
        if(domains.isNotEmpty()) {
            lastAttempt = now
            if(fullCheck) lastFullCheck = now
        }
        return domains
    }

    fun completedFullSync(binding: WebDavConfig) {
        selectBinding(binding)
        val now = nowMillis()
        lastAttempt = now
        lastFullCheck = now
    }

    private fun selectBinding(binding: WebDavConfig) {
        if(generation == binding.generation) return
        generation = binding.generation
        lastAttempt = null
        lastFullCheck = null
    }
}

/** 冷却按服务来源和账号共享，不因目录、授权码或配置代次变化而绕过。 */
internal fun webDavAccountKey(binding: WebDavConfig): String {
    val endpoint = WebDavPaths.endpoint(binding)
    val identity = "${endpoint.scheme}://${endpoint.host}:${endpoint.port}\n${binding.username}"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class WebDavRequestCooldown(
    private val nowMillis: () -> Long,
    private val readUntil: (String) -> Long,
    private val writeUntil: (String, Long) -> Unit,
) {
    private val memory = mutableMapOf<String, Long>()

    @Synchronized fun check(account: String) {
        val remaining = maxOf(memory[account] ?: 0L, readUntil(account)) - nowMillis()
        if(remaining > 0) {
            val minutes = (remaining / 60_000 + if(remaining % 60_000 > 0) 1 else 0).coerceAtLeast(1)
            throw WebDavException(WebDavFailure.RATE_LIMITED,
                "WebDAV 请求已暂停，请约 $minutes 分钟后重试；本机修改已保留", retryAfterMillis = remaining)
        }
    }

    @Synchronized fun record(account: String, error: WebDavException) {
        if(error.failure != WebDavFailure.RATE_LIMITED) return
        val now = nowMillis()
        val delay = error.retryAfterMillis ?: 30 * 60_000L
        val until = maxOf(memory[account] ?: 0L, readUntil(account), now + delay.coerceAtMost(Long.MAX_VALUE - now))
        memory[account] = until
        writeUntil(account, until)
    }
}
