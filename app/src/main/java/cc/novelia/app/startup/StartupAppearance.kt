package cc.novelia.app.startup

import android.content.Context
import android.content.SharedPreferences
import cc.novelia.app.R
import cc.novelia.app.data.model.APP_THEME_MODES
import cc.novelia.app.data.model.MONOCHROME_APP_THEME
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class StartupAppearance(val theme: String = "system", val amoledBlack: Boolean = false) {
    val splashVariant: String get() = when(theme) {
        "light" -> "Light"
        MONOCHROME_APP_THEME -> "Monochrome"
        "dark" -> if(amoledBlack) "Amoled" else "Dark"
        else -> if(amoledBlack) "SystemAmoled" else ""
    }

    val windowTheme: Int get() = when(splashVariant) {
        "Light" -> R.style.Theme_Novelia_Light
        "Monochrome" -> R.style.Theme_Novelia_Monochrome
        "Dark" -> R.style.Theme_Novelia_Dark
        "Amoled" -> R.style.Theme_Novelia_Amoled
        "SystemAmoled" -> R.style.Theme_Novelia_SystemAmoled
        else -> R.style.Theme_Novelia
    }
}

/** 只缓存启动前需要的两个显示字段，避免主线程提前读取整个书库；书库加载后校准。 */
internal class StartupAppearanceStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("startup_appearance", Context.MODE_PRIVATE))

    private val mutable = MutableStateFlow(StartupAppearance(
        preferences.getString("theme", "system")?.takeIf { it in APP_THEME_MODES } ?: "system",
        preferences.getBoolean("amoledBlack", false)
    ))
    val state = mutable.asStateFlow()

    @Synchronized fun update(appearance: StartupAppearance) {
        if(appearance == mutable.value) return
        preferences.edit().putString("theme", appearance.theme).putBoolean("amoledBlack", appearance.amoledBlack).apply()
        mutable.value = appearance
    }
}
