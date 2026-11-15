package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

internal const val MONOCHROME_APP_THEME = "monochrome"
internal val APP_THEME_MODES = mapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色", MONOCHROME_APP_THEME to "黑白")

internal fun LibraryState.useAppPresets(presets: ThemePresets) = copy(
    theme = if (theme == MONOCHROME_APP_THEME) "system" else theme, themePresets = presets,
)

@Serializable
data class ThemeBarGlass(val enabled: Boolean = false, val blur: Int = 65, val opacity: Int = 45) {
    val valid: Boolean get() = blur in 0..100 && opacity in 0..100
}

/** 图片引用只保存内容散列，设备路径和文件选择器的临时授权不进入偏好。 */
@Serializable
data class ThemePreset(
    val id: String,
    val name: String,
    val accent: Long = 0x3E6B3B,
    val lightBackground: Long = 0xF1F4EC,
    val darkBackground: Long = 0x101512,
    val image: String? = null,
    val blur: Int = 0,
    val overlay: Int = 60,
    val cardOpacity: Int = 88,
    val topBar: ThemeBarGlass = ThemeBarGlass(),
    val bottomBar: ThemeBarGlass = ThemeBarGlass(),
) {
    val valid: Boolean get() = idPattern.matches(id) && name.isNotBlank() && name.length <= MAX_NAME &&
        listOf(accent, lightBackground, darkBackground).all { it in 0L..0xFFFFFFL } &&
        (image == null || imagePattern.matches(image)) && blur in 0..100 && overlay in 0..100 && cardOpacity in 50..100 &&
        topBar.valid && bottomBar.valid

    companion object {
        const val MAX_NAME = 40
        internal val idPattern = Regex("[a-zA-Z0-9_-]{1,64}")
        internal val imagePattern = Regex("[a-f0-9]{64}")
    }
}

@Serializable
data class ThemePresets(val items: List<ThemePreset> = emptyList(), val activeId: String? = null) {
    val active: ThemePreset? get() = activeId?.let { id -> items.firstOrNull { it.id == id && it.valid } }
    val images: Set<String> get() = items.mapNotNullTo(mutableSetOf()) { it.image }

    fun requireValid() {
        require(items.size <= MAX_PRESETS && items.all { it.valid } && items.distinctBy { it.id }.size == items.size &&
            (activeId == null || items.any { it.id == activeId })) { "自定义主题配置无效" }
    }

    fun saveAndSelect(preset: ThemePreset): ThemePresets {
        require(preset.valid) { "请检查主题名称、配色和背景设置" }
        val existing = items.any { it.id == preset.id }
        require(existing || items.size < MAX_PRESETS) { "最多可保存 ${MAX_PRESETS} 套自定义主题" }
        val updated = if (existing) items.map { if (it.id == preset.id) preset else it } else items + preset
        return ThemePresets(updated, preset.id)
    }

    fun remove(id: String) = ThemePresets(items.filterNot { it.id == id }, activeId?.takeUnless { it == id })

    companion object { const val MAX_PRESETS = 32 }
}
