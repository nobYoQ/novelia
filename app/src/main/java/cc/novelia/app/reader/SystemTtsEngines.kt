package cc.novelia.app.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech

internal data class SystemTtsEngine(val packageName: String, val label: String)
internal data class SystemTtsCatalog(val engines: List<SystemTtsEngine> = emptyList(), val systemDefault: String? = null)

/** 只枚举声明了系统 TTS 服务的应用，不初始化引擎或合成语音。 */
internal fun readSystemTtsCatalog(context: Context): SystemTtsCatalog {
    val manager = context.packageManager
    val engines = manager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        .filter { it.serviceInfo?.let { service -> service.exported && service.enabled && service.applicationInfo.enabled } == true }
        .map { info ->
            val name = info.serviceInfo.packageName
            SystemTtsEngine(name, runCatching { info.loadLabel(manager).toString().ifBlank { name } }.getOrDefault(name))
        }
        .distinctBy { it.packageName }.sortedBy { it.label }
    val default = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) }.getOrNull()
    return SystemTtsCatalog(engines, default)
}

/** 未安装或卸载的设备专属引擎回退到系统默认，保留偏好以便重新安装后继续使用。 */
internal fun selectedSystemTtsEngine(preferred: String?, engines: List<SystemTtsEngine>): String? =
    preferred?.takeIf { name -> engines.any { it.packageName == name } }

internal fun openSystemTtsSettings(context: Context): Boolean =
    listOf("com.android.settings.TTS_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)
        .any { startTtsActivity(context, Intent(it)) }

internal fun openTtsEngineApp(context: Context, packageName: String): Boolean {
    val launcher = context.packageManager.getLaunchIntentForPackage(packageName)
    return launcher?.let { startTtsActivity(context, it) } == true || openTtsVoiceInstaller(context, packageName)
}

internal fun openTtsVoiceInstaller(context: Context, packageName: String): Boolean =
    startTtsActivity(context, Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(packageName))

private fun startTtsActivity(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
} catch(_: ActivityNotFoundException) { false } catch(_: SecurityException) { false }
