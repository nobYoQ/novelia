package cc.novelia.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import cc.novelia.app.BuildConfig
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString

/** Read only coarse network capabilities; no SSID/location or hardware IDs. */
internal fun networkEnvironment(context: Context, echEnabled: Boolean, recording: Boolean): String {
    val fields = linkedMapOf(
        "schema" to "1", "capturedAtMs" to System.currentTimeMillis().toString(),
        "appVersion" to BuildConfig.VERSION_NAME, "appVersionCode" to BuildConfig.VERSION_CODE.toString(),
        "androidApi" to Build.VERSION.SDK_INT.toString(), "androidVersion" to Build.VERSION.RELEASE,
        "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "abis" to Build.SUPPORTED_ABIS.joinToString(","),
        "echEnabled" to echEnabled.toString(), "recording" to recording.toString(),
        "echAddressPolicy" to "IPv4-only", "echSystemHttpProxy" to "not_used",
        "echConnectAttemptMs" to "8000", "echReadOnlyConnectAttempts" to "3"
    )
    try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val properties = network?.let { manager.getLinkProperties(it) }
        fields["activeNetwork"] = (network != null).toString()
        if (capabilities != null) {
            fields["transports"] = listOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet", NetworkCapabilities.TRANSPORT_VPN to "vpn")
                .filter { capabilities.hasTransport(it.first) }.joinToString(",") { it.second }
            fields["validated"] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).toString()
            fields["captivePortal"] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL).toString()
            fields["metered"] = (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).toString()
        }
        if (properties != null) {
            fields["ipv4Configured"] = properties.linkAddresses.any { it.address.address.size == 4 }.toString()
            fields["ipv6Configured"] = properties.linkAddresses.any { it.address.address.size == 16 }.toString()
            fields["systemDnsCount"] = properties.dnsServers.size.toString()
            fields["httpProxyConfigured"] = (properties.httpProxy != null).toString()
            if (Build.VERSION.SDK_INT >= 28) fields["privateDnsActive"] = properties.isPrivateDnsActive.toString()
        }
    } catch (_: Exception) { fields["networkMetadata"] = "unavailable" }
    return appJson.encodeToString(fields)
}
