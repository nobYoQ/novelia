package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

/** The settings changed by the e-ink preset, saved separately for each side of its toggle. */
@Serializable data class ReaderPagingState(
    val paginationMode: String = "scroll", val scrollPageTurn: Boolean = false,
    val horizontalPageTurn: Boolean = false, val showPageButtons: Boolean = false, val volumeKeys: Boolean = false
)

/**
 * 可序列化的阅读偏好，全局默认和单书覆盖使用同一结构；字段默认值兼容旧版本缺失字段。
 * 字号使用 sp，宽度和段距使用 dp，lineHeight 是字号倍数，brightness 为负表示跟随系统。
 * paged/monochrome 等旧字段保留用于迁移；渲染应使用 staticPagination 和 resolvedTheme。
 */
@Serializable data class ReaderSettings(
    val mode: String = "zh", val engines: List<String> = listOf("sakura", "gpt", "youdao"), val parallel: Boolean = false,
    val fontSize: Float = 19f, val lineHeight: Float = 1.8f, val weight: Boolean = false, val width: Float = 720f,
    val indent: Boolean = true, val theme: String = "system", val secondaryAlpha: Float = .65f, val underline: Boolean = false,
    val keepScreenOn: Boolean = false, val volumeKeys: Boolean = false, val paged: Boolean = false, val eInkMode: Boolean = false,
    val monochrome: Boolean = false, val scrollPageTurn: Boolean = eInkMode, val horizontalPageTurn: Boolean = eInkMode,
    val brightness: Float = -1f, val speechRate: Float = 1f, val speechMinutes: Int = 30, val traditional: Boolean = false, val speechLanguage: String = "auto",
    // Defaults migrate the old implicit paging mode and always-visible static-page buttons.
    val paginationMode: String = if(eInkMode || scrollPageTurn || horizontalPageTurn) "auto" else "scroll",
    val showPageButtons: Boolean = paged || paginationMode == "auto",
    val beforeEInk: ReaderPagingState? = null, val eInkPreferences: ReaderPagingState? = null,
    val toolbarTransparency: Float = .25f,
    val prefetchChapters: Int = 3, val prefetchWifiOnly: Boolean = true,
    val paragraphSpacing: Float = 8f,
    // List/form paging controls are independent of the reader's page buttons and e-ink presets.
    val showScrollPageButtons: Boolean = true
) {
    val resolvedParagraphSpacing get() = if(paragraphSpacing.isFinite()) paragraphSpacing.coerceIn(0f, 32f) else 8f
    val resolvedToolbarTransparency get() = if(toolbarTransparency.isFinite()) toolbarTransparency.coerceIn(0f, 1f) else .25f
    // Compatibility with settings saved before black-and-white became a theme choice.
    val resolvedTheme get() = if(monochrome) "monochrome" else theme
    fun withTheme(selected: String) = copy(theme = selected, monochrome = false)
    val staticPagination get() = paginationMode == "auto"
    fun withPaginationMode(selected: String): ReaderSettings {
        // Give the first switch from a scrolling reader usable gestures. Existing choices survive mode changes.
        val needsGestures = selected == "auto" && !staticPagination && !scrollPageTurn && !horizontalPageTurn && !showPageButtons
        return copy(paginationMode = selected, scrollPageTurn = scrollPageTurn || needsGestures,
            horizontalPageTurn = horizontalPageTurn || needsGestures)
    }
    private fun pagingState() = ReaderPagingState(paginationMode, scrollPageTurn, horizontalPageTurn, showPageButtons, volumeKeys)
    private fun withPagingState(state: ReaderPagingState) = copy(paginationMode = state.paginationMode,
        scrollPageTurn = state.scrollPageTurn, horizontalPageTurn = state.horizontalPageTurn,
        showPageButtons = state.showPageButtons, volumeKeys = state.volumeKeys)
    /**
     * 切换电子纸时分别记住普通模式和电子纸模式的翻页偏好，而不是每次套用固定预设。
     * 首次开启才使用默认电子纸手势；主题等非翻页设置不在此处重置。
     */
    fun withEInkMode(enabled: Boolean): ReaderSettings {
        if(enabled == eInkMode) return this
        return if(enabled) {
            val target = eInkPreferences ?: ReaderPagingState(paginationMode = "auto", scrollPageTurn = true,
                horizontalPageTurn = true, showPageButtons = true, volumeKeys = true)
            withPagingState(target).copy(eInkMode = true, beforeEInk = pagingState())
        } else {
            // Older versions never recorded the prior mode. Fall back to the original scrolling defaults.
            val target = beforeEInk ?: ReaderPagingState(showPageButtons = paged)
            withPagingState(target).copy(eInkMode = false, beforeEInk = null, eInkPreferences = pagingState())
        }
    }
}
