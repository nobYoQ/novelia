package cc.novelia.app.startup

import android.content.Context
import android.content.SharedPreferences
import cc.novelia.app.R
import cc.novelia.app.data.model.APP_THEME_MODES
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.MONOCHROME_APP_THEME
import cc.novelia.app.data.model.ThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private fun startupPalette(accent: Long, light: Long, dark: Long) =
    ThemePreset("startup", "启动配色", accent, light, dark).takeIf { it.valid }

internal fun LibraryState.startupAppearance() = StartupAppearance(theme, amoledBlack,
    if(reader.eInkMode || theme == MONOCHROME_APP_THEME) null else themePresets.active?.let {
        startupPalette(it.accent, it.lightBackground, it.darkBackground)
    })

internal data class StartupAppearance(val theme: String = "system", val amoledBlack: Boolean = false, val palette: ThemePreset? = null) {
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

internal class StartupAppearanceStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("startup_appearance", Context.MODE_PRIVATE))

    private val mutable = MutableStateFlow(StartupAppearance(
        preferences.getString("theme", "system")?.takeIf { it in APP_THEME_MODES } ?: "system",
        preferences.getBoolean("amoledBlack", false),
        startupPalette(preferences.getLong("accent", -1), preferences.getLong("lightBackground", -1), preferences.getLong("darkBackground", -1))
    ))
    val state = mutable.asStateFlow()

    @Synchronized fun update(appearance: StartupAppearance) {
        if(appearance == mutable.value) return
        val edit = preferences.edit().putString("theme", appearance.theme).putBoolean("amoledBlack", appearance.amoledBlack)
        val palette = appearance.palette
        if(palette == null) edit.remove("accent").remove("lightBackground").remove("darkBackground")
        else edit.putLong("accent", palette.accent).putLong("lightBackground", palette.lightBackground).putLong("darkBackground", palette.darkBackground)
        edit.apply()
        mutable.value = appearance
    }
}
