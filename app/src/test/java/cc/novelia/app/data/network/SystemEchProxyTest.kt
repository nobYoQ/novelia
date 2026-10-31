package cc.novelia.app.data.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class SystemEchProxyTest {
    @Test fun nativeBridgeReceivesExplicitDefaultPortAndBracketedIPv6() {
        for ((host, port, expected) in listOf(Triple("localhost", 80, "http://localhost:80/"),
            Triple("::1", 8080, "http://[::1]:8080/"))) {
            val selector = object : ProxySelector() {
                override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))
                override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
            }
            assertEquals(expected, systemEchProxy("https://n.novelia.cc/", selector))
        }
    }

    @Test fun proxyAndBypassRulesApplyPerDestination() {
        val selected = mutableListOf<String>()
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                selected += uri.host
                return if (uri.host == "direct.invalid") listOf(Proxy.NO_PROXY)
                else listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("localhost", 8888)))
            }
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
        }
        assertEquals("http://localhost:8888/", systemEchProxy("https://n.novelia.cc/", selector))
        assertEquals("http://localhost:8888/", systemEchProxy("https://dns.alidns.com/dns-query", selector))
        assertEquals("", systemEchProxy("https://direct.invalid/", selector))
        assertEquals(listOf("n.novelia.cc", "dns.alidns.com", "direct.invalid"), selected)
    }

    @Test fun unsupportedFirstProxyDoesNotSilentlyUseDirectFallback() {
        val selector = object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080)), Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
        }
        assertTrue(runCatching { systemEchProxy("https://n.novelia.cc/", selector) }.exceptionOrNull() is IOException)
        assertEquals("", systemEchProxy("https://n.novelia.cc/", null))
    }
}
