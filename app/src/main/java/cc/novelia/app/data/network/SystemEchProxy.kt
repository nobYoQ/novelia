package cc.novelia.app.data.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import okhttp3.HttpUrl

/** Uses Android's selector for each destination so PAC and exclusions also apply to DoH. */
internal fun systemEchProxy(url: String, selector: ProxySelector? = ProxySelector.getDefault()): String {
    val candidates = try { selector?.select(URI(url)) } catch (_: Exception) { throw IOException("系统代理选择失败") }
    val proxy = candidates?.firstOrNull() ?: if (selector == null) return "" else throw IOException("系统代理没有可用路由")
    if (proxy.type() == Proxy.Type.DIRECT) return ""
    if (proxy.type() != Proxy.Type.HTTP) throw IOException("ECH 暂不支持此代理类型")
    val address = proxy.address() as? InetSocketAddress ?: throw IOException("系统代理地址无效")
    return try {
        val validated = HttpUrl.Builder().scheme("http").host(address.hostString).port(address.port).build()
        val host = if (validated.host.contains(':')) "[${validated.host}]" else validated.host
        // The native socket bridge needs an explicit port, including HTTP's default port 80.
        "http://$host:${validated.port}/"
    }
    catch (_: Exception) { throw IOException("系统代理地址无效") }
}
