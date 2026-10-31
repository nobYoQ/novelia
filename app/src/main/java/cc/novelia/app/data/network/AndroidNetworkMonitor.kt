package cc.novelia.app.data.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Proxy
import android.os.Build
import androidx.core.content.ContextCompat

/** Application lifetime. Callback values are authoritative; do not re-query a network in a callback. */
internal class AndroidNetworkMonitor(context: Context, private val changes: NetworkChanges) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changes.available(network.networkHandle)
        override fun onLost(network: Network) = changes.lost(network.networkHandle)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val transports = (0..8).filter { capabilities.hasTransport(it) }.toSet()
            changes.capabilities(network.networkHandle, transports,
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            // Kept in memory for equality only. Never log addresses, proxy hosts or DNS names.
            val links = buildList {
                add("interface:${properties.interfaceName}")
                if (Build.VERSION.SDK_INT >= 29) add("mtu:${properties.mtu}")
                addAll(properties.dnsServers.map { "dns:${it.hostAddress}" })
                addAll(properties.linkAddresses.map { "address:$it" }.sorted())
                addAll(properties.routes.map { "route:$it" }.sorted())
                add("proxy:${properties.httpProxy}")
                if (Build.VERSION.SDK_INT >= 28) {
                    add("privateDns:${properties.isPrivateDnsActive}:${properties.privateDnsServerName}")
                }
            }
            changes.links(network.networkHandle, links)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Proxy.PROXY_CHANGE_ACTION) refreshProxy()
        }
    }

    fun start() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Proxy.PROXY_CHANGE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshProxy()
        manager?.registerDefaultNetworkCallback(callback)
    }

    private fun refreshProxy() = changes.proxy(manager?.defaultProxy?.toString())
}
