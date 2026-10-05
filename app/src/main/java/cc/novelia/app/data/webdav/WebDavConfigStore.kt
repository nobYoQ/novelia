package cc.novelia.app.data.webdav

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import cc.novelia.app.data.auth.DeviceCipher
import cc.novelia.app.data.storage.appJson
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 本机专用配置，不进入书库、普通设置导出或阅读资料备份。 */
@Serializable
data class WebDavConfig(
    val endpoint: String = "",
    val username: String = "",
    val folder: String = "novelia-sync",
    val deviceName: String = "",
    val enabled: Boolean = false,
    val automatic: Boolean = true,
    val selected: Set<SyncDomain> = SyncDomain.entries.toSet(),
    val syncDevicePreferences: Boolean = false,
    val wifiOnly: Boolean = true,
    val intervalMinutes: Long = 30,
    val generation: Long = 0,
    val deviceId: String = "",
    val datasetId: String? = null,
    val allowInsecureHttp: Boolean = false,
) {
    val bound: Boolean get() = datasetId != null
}

class WebDavConfigChangedException : IllegalStateException("同步配置已变化，请重新操作")

// KTX edit(commit=true) 忽略 commit 的结果；此处必须确认落盘成功后才发布新配置。
@SuppressLint("UseKtx")
class WebDavConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("webdav-sync", Context.MODE_PRIVATE)
    private val cipher = DeviceCipher("novelia.webdav.credentials")
    private val mutableConfig: MutableStateFlow<WebDavConfig>
    val config: StateFlow<WebDavConfig>

    init {
        val loaded = runCatching { preferences.getString("configuration", null)?.let {
            appJson.decodeFromString<WebDavConfig>(it)
        } }.getOrNull() ?: WebDavConfig()
        val initial = loaded.copy(
            deviceId = loaded.deviceId.ifBlank { UUID.randomUUID().toString() },
            deviceName = loaded.deviceName.ifBlank { Build.MODEL.orEmpty().ifBlank { "此设备" } },
        )
        mutableConfig = MutableStateFlow(initial)
        config = mutableConfig.asStateFlow()
        check(preferences.edit().putString("configuration", appJson.encodeToString(initial)).commit()) { "无法保存同步配置" }
    }

    /** null 表示保留密码；空字符串表示明确清空。保存成功才向调度器发布配置。 */
    @Synchronized
    fun save(value: WebDavConfig, password: String? = null): WebDavConfig {
        val old = mutableConfig.value
        require(value.intervalMinutes in setOf(15L, 30L, 60L, 360L)) { "请选择有效的后台检查间隔" }
        require(value.deviceName.trim().length in 1..80) { "设备名称需要 1～80 个字符" }
        require(value.deviceName.none { it.isISOControl() }) { "设备名称不能包含控制字符" }
        require(value.username.length <= 512 && ':' !in value.username && value.username.none { it.isISOControl() }) { "用户名格式无效" }
        require(password == null || password.length <= 16_384) { "密码过长" }
        val endpoint = if(value.endpoint.isBlank() && !value.enabled) "" else WebDavPaths.endpoint(value).toString()
        val folder = WebDavPaths.folderSegments(value.folder).joinToString("/")
        require(!value.enabled || value.selected.isNotEmpty()) { "请至少选择一项同步内容" }
        val sameServer = old.endpoint == endpoint && old.folder == folder && old.username == value.username
        val normalized = value.copy(endpoint = endpoint, folder = folder, deviceName = value.deviceName.trim(),
            generation = old.generation, deviceId = old.deviceId,
            datasetId = if(sameServer) old.datasetId else null)
        if(normalized == old && password == null) return old
        val saved = normalized.copy(generation = Math.addExact(old.generation, 1))
        val editor = preferences.edit().putString("configuration", appJson.encodeToString(saved))
        if(password != null) {
            if(password.isEmpty()) editor.remove("password") else editor.putString("password", cipher.encrypt(password))
        } else if(!sameServer) {
            // 地址或账号变化时不把旧服务的凭据带给新服务。
            editor.remove("password")
        }
        check(editor.commit()) { "无法保存同步配置" }
        mutableConfig.value = saved
        return saved
    }

    @Synchronized
    fun password(binding: WebDavConfig): String {
        ensureCurrent(binding.generation)
        val stored = preferences.getString("password", null) ?: return ""
        return runCatching { cipher.decrypt(stored) }.getOrElse {
            throw IllegalStateException("无法读取此设备保存的密码，请重新填写")
        }
    }

    @Synchronized
    fun bindDataset(generation: Long, id: String) {
        ensureCurrent(generation)
        require(id.isNotBlank() && id.length <= 160) { "远端数据标识无效" }
        val saved = mutableConfig.value.copy(datasetId = id, enabled = true)
        check(preferences.edit().putString("configuration", appJson.encodeToString(saved)).commit()) { "无法保存同步配置" }
        mutableConfig.value = saved
    }

    @Synchronized
    fun disable() { save(mutableConfig.value.copy(enabled = false)) }

    @Synchronized
    fun ensureCurrent(generation: Long) {
        if(mutableConfig.value.generation != generation) throw WebDavConfigChangedException()
    }

    /** 仅保护很短的本地状态提交；调用方不能在此等待网络或落盘。 */
    @Synchronized
    fun <T> withBinding(generation: Long, block: () -> T): T {
        ensureCurrent(generation)
        return block()
    }
}
