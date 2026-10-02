package cc.novelia.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/** Application-scoped ECH entry point. Business clients share one engine and device preference. */
class EchTransport(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences("ech-transport", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = state.asStateFlow()
    private val engine = EchNativeEngine(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recordingState = MutableStateFlow(false)
    val recording = recordingState.asStateFlow()
    private var recordingJob: Job? = null
    private val recorder = NetworkRecorder(NetworkLogStore(File(applicationContext.noBackupFilesDir, "network-logs"))) { recordingState.value }
    private val diagnosisState = MutableStateFlow(NetworkDiagnosisState())
    internal val diagnosis = diagnosisState.asStateFlow()
    private val diagnosisMutex = Mutex()
    private var diagnosisJob: Job? = null
    // Running diagnostics must not reset the connections used by active API calls or downloads.
    private val diagnosticEngine = EchNativeEngine(applicationContext)
    private val diagnosticEchClient by lazy { diagnosticClient(true) }
    private val diagnosticDirectClient by lazy { diagnosticClient(false) }
    private val httpClient by lazy {
        recorder.attach(OkHttpClient.Builder()) { state.value }
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
                override fun onAvailable(network: Network) { recorder.system("network", "available"); networkChanged() }
                override fun onLost(network: Network) { recorder.system("network", "lost"); networkChanged() }
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    recorder.system("network", "properties_changed", environment())
                }
            })
    }

    private fun networkChanged() {
        engine.resetNetworkState()
        diagnosticEngine.resetNetworkState()
    }

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        state.value = value
        recorder.system("ech_setting", if (value) "enabled" else "disabled")
    }

    /** Reuse for novel/forum APIs and their sessions; derive download/image clients from this one. */
    fun client(): OkHttpClient = httpClient

    private fun diagnosticClient(ech: Boolean) = recorder.attach(OkHttpClient.Builder()) { ech }
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).apply { if (ech) addInterceptor(EchInterceptor(diagnosticEngine, { true })) }.build()

    @Synchronized fun setRecording(value: Boolean) {
        recordingJob?.cancel()
        recordingState.value = value
        recorder.system("recording", if (value) "started" else "stopped", environment())
        recordingJob = if (value) scope.launch {
            delay(30 * 60 * 1000L)
            recordingState.value = false
            recorder.system("recording", "expired")
        } else null
    }

    @Synchronized fun startDiagnostics() {
        if (diagnosisJob?.isActive == true || diagnosisState.value.running) return
        diagnosisJob = scope.launch { diagnose() }
    }

    fun cancelDiagnostics() { diagnosisJob?.cancel() }

    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        diagnosisMutex.withLock {
            diagnosisState.value = NetworkDiagnosisState(running = true)
            recorder.system("diagnosis", "started", environment())
            try {
                // Both clients are isolated from the business connection pool and account session.
                diagnosticEngine.resetNetworkState()
                diagnosticDirectClient.connectionPool.evictAll()
                val report = withTimeoutOrNull(180_000) {
                    EchDiagnostics(diagnosticEchClient, diagnosticDirectClient).run { report, completed, total ->
                        diagnosisState.value = NetworkDiagnosisState(true, completed, total, report)
                        recorder.saveReport(report)
                    }
                }
                if (report == null) {
                    diagnosisState.value = diagnosisState.value.copy(report = diagnosisState.value.report + "\n\n达到 180 秒总时限，剩余检查已取消；已完成结果和阶段日志可导出。")
                    recorder.system("diagnosis", "timeout")
                } else recorder.system("diagnosis", "complete")
            } catch (cancelled: CancellationException) {
                diagnosisState.value = diagnosisState.value.copy(report = diagnosisState.value.report + "\n\n诊断已取消，已完成结果和阶段日志可导出。")
                recorder.system("diagnosis", "cancelled")
                throw cancelled
            } catch (_: LinkageError) {
                diagnosisState.value = diagnosisState.value.copy(report = "当前设备无法加载 ECH 本地库，请导出日志。")
                recorder.system("diagnosis", "native_library")
            } catch (_: Exception) {
                diagnosisState.value = diagnosisState.value.copy(report = diagnosisState.value.report + "\n\n诊断未能完成，请导出已有日志。")
                recorder.system("diagnosis", "failed")
            } finally {
                diagnosisState.value = diagnosisState.value.copy(running = false)
                recorder.saveReport(diagnosisState.value.report)
            }
            diagnosisState.value.report
        }
    }

    private fun environment() = networkEnvironment(applicationContext, state.value, recordingState.value)
    suspend fun exportNetworkLogs(): ByteArray = recorder.export(environment())

    suspend fun clearNetworkLogs() {
        recorder.clear()
        diagnosisState.value = NetworkDiagnosisState()
    }

    suspend fun resetConnections() = withContext(Dispatchers.IO) { networkChanged() }
}

internal data class NetworkDiagnosisState(val running: Boolean = false, val completed: Int = 0, val total: Int = 14, val report: String = "")
