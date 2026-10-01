package cc.novelia.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Application-scoped ECH entry point. Business clients share one engine and device preference. */
class EchTransport(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences("ech-transport", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = state.asStateFlow()
    private val engine = EchNativeEngine(applicationContext)
    // Running diagnostics must not reset the connections used by active API calls or downloads.
    private val diagnosticEngine = EchNativeEngine(applicationContext)
    private val diagnostics = EchDiagnostics(diagnosticEngine) { host ->
        withContext(Dispatchers.IO) { diagnosticEngine.probe(host) }
    }
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .addInterceptor(EchInterceptor(engine, { state.value }))
            .build()
    }

    init {
        applicationContext.getSystemService(ConnectivityManager::class.java)
            ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = networkChanged()
                override fun onLost(network: Network) = networkChanged()
            })
    }

    private fun networkChanged() {
        engine.resetNetworkState()
        diagnosticEngine.resetNetworkState()
    }

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        state.value = value
    }

    /** Reuse for novel/forum APIs and their sessions; derive download/image clients from this one. */
    fun client(): OkHttpClient = httpClient

    suspend fun diagnose(): String {
        withContext(Dispatchers.IO) { diagnosticEngine.resetNetworkState() }
        return diagnostics.run()
    }

    suspend fun resetConnections() = withContext(Dispatchers.IO) { networkChanged() }
}
