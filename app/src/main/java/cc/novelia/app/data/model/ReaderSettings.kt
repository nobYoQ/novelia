package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

/** 不透明 RGB 配色作为一组保存和同步，避免混用不同设备的文字与背景颜色。 */
@Serializable data class ReaderCustomColors(
    val text: Long = 0x282E27, val background: Long = 0xF4ECD8, val toolbar: Long = 0xDED2B8
) {
    val valid get() = listOf(text, background, toolbar).all { it in 0L..0xFFFFFFL }
}

/** 两种阅读模式的独立快照；保留旧类名和翻页字段以兼容历史设置。新增字段缺省时继承当前值。 */
@Serializable data class ReaderPagingState(
    val paginationMode: String = "scroll", val scrollPageTurn: Boolean = false,
    val horizontalPageTurn: Boolean = false, val showPageButtons: Boolean = false, val volumeKeys: Boolean = false,
    val mode: String? = null,
    val engines: List<String>? = null,
    val parallel: Boolean? = null,
    val fontSize: Float? = null,
    val lineHeight: Float? = null,
    val weight: Boolean? = null,
    val width: Float? = null,
    val indent: Boolean? = null,
    val theme: String? = null,
    val secondaryAlpha: Float? = null,
    val underline: Boolean? = null,
    val keepScreenOn: Boolean? = null,
    val paged: Boolean? = null,
    val monochrome: Boolean? = null,
    val brightness: Float? = null,
    val speechRate: Float? = null,
    val speechMinutes: Int? = null,
    val traditional: Boolean? = null,
    val speechLanguage: String? = null,
    val toolbarTransparency: Float? = null,
    val prefetchChapters: Int? = null,
    val prefetchWifiOnly: Boolean? = null,
    val paragraphSpacing: Float? = null,
    val showScrollPageButtons: Boolean? = null,
    val showProgressBar: Boolean? = null,
    val showReadingStatusBar: Boolean? = null,
    val speechContinueChapters: Boolean? = null,
    val speechNetworkContinuation: Boolean? = null,
    val showEInkScreenButtons: Boolean? = null,
    val hideStatusBar: Boolean? = null,
    val tapPageTurn: Boolean? = null,
    val tapZoneMap: String? = null,
    val customColors: ReaderCustomColors? = null,
    val fontFamily: String? = null,
    val customFont: ReaderCustomFont? = null,
    val horizontalMargin: Float? = null,
    val verticalMargin: Float? = null,
    val showWideToc: Boolean? = null,
    val orientation: String? = null,
    val autoReadSpeed: Float? = null,
    val autoReadIntervalSeconds: Int? = null,
    val textReplacements: Map<String, String>? = null,
    val textReplacementsEnabled: Boolean? = null,
    val readingStatusItems: List<String>? = null,
    val showEpubRuby: Boolean? = null,
    val epubFootnotePopup: Boolean? = null,
    val showEpubStyles: Boolean? = null
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
    // 通过默认值迁移旧版隐式分页模式和始终显示的静态翻页按钮。
    val paginationMode: String = if(eInkMode || scrollPageTurn || horizontalPageTurn) "auto" else "scroll",
    val showPageButtons: Boolean = paged || paginationMode == "auto",
    val beforeEInk: ReaderPagingState? = null, val eInkPreferences: ReaderPagingState? = null,
    val toolbarTransparency: Float = if(eInkMode) 0f else .25f,
    val prefetchChapters: Int = 3, val prefetchWifiOnly: Boolean = true,
    val paragraphSpacing: Float = 8f,
    // 连续阅读的章末导航独立于工具栏按钮。
    val showScrollPageButtons: Boolean = true,
    // 章节进度条独立于翻页按钮。
    val showProgressBar: Boolean = true,
    // 两种阅读模式共用的常驻底栏，独立于展开工具栏时的进度滑条。
    val showReadingStatusBar: Boolean = true,
    // 默认连读本地/缓存及联网章节，整个播放会话共用一次定时停止。
    val speechContinueChapters: Boolean = true,
    val speechNetworkContinuation: Boolean = true,
    val showEInkScreenButtons: Boolean = true,
    val hideStatusBar: Boolean = false,
    // 点击区域独立于滑动手势，旧设置保持整页点击工具栏的行为。
    val tapPageTurn: Boolean = false,
    // 点击分区方案（含每格动作）的编码值，移植自 FBReaderJ；空串沿用默认四向翻页。
    val tapZoneMap: String = "",
    val customColors: ReaderCustomColors = ReaderCustomColors(),
    // 字体文件仅保存在本机；切换系统字体时保留已导入字体，便于切回。
    val fontFamily: String = "system",
    val customFont: ReaderCustomFont? = null,
    val horizontalMargin: Float = 24f, val verticalMargin: Float = 16f,
    val showWideToc: Boolean = true,
    val orientation: String = "system",
    // 自动阅读仅记住速度；运行状态属于当前前台阅读会话，不随备份或重启恢复。
    val autoReadSpeed: Float = 20f, val autoReadIntervalSeconds: Int = 15,
    val textReplacements: Map<String, String> = emptyMap(), val textReplacementsEnabled: Boolean = true,
    val readingStatusItems: List<String> = ReaderStatusItems.defaults,
    // 只影响附加显示；导入元数据、底文与阅读锚点始终保留。
    val showEpubRuby: Boolean = true,
    val epubFootnotePopup: Boolean = true,
    val showEpubStyles: Boolean = true
) {
    companion object {
        const val MIN_LINE_HEIGHT = .5f
        val LINE_HEIGHT_RANGE = MIN_LINE_HEIGHT..2.6f
        val THEMES = listOf("system", "paper", "light", "dark", "monochrome", "custom")
        val ORIENTATIONS = listOf("system", "portrait", "landscape")
        val SPEECH_TIMER_OPTIONS = listOf(0, 15, 30, 60)
        const val MAX_SPEECH_MINUTES = 1_440
        fun speechTimerLabel(minutes: Int) = if(minutes == 0) "不限时" else "$minutes 分钟"
    }

    // 0 是明确的不限时选择；损坏的负数配置仍回退到默认定时。
    val resolvedSpeechMinutes get() = if(speechMinutes < 0) 30 else speechMinutes.coerceAtMost(MAX_SPEECH_MINUTES)
    val validSpeechTimer get() = speechMinutes in 0..MAX_SPEECH_MINUTES
    val resolvedParagraphSpacing get() = if(paragraphSpacing.isFinite()) paragraphSpacing.coerceIn(0f, 32f) else 8f
    val resolvedHorizontalMargin get() = if(horizontalMargin.isFinite()) horizontalMargin.coerceIn(0f, 64f) else 24f
    val resolvedVerticalMargin get() = if(verticalMargin.isFinite()) verticalMargin.coerceIn(0f, 64f) else 16f
    val resolvedOrientation get() = orientation.takeIf { it in ORIENTATIONS } ?: "system"
    val resolvedAutoReadSpeed get() = if(autoReadSpeed.isFinite()) autoReadSpeed.coerceIn(5f, 80f) else 20f
    val resolvedAutoReadIntervalSeconds get() = autoReadIntervalSeconds.coerceIn(5, 120)
    val validAutoRead get() = autoReadSpeed in 5f..80f && autoReadIntervalSeconds in 5..120
    val validTextReplacements get() = validReaderTextReplacements(textReplacements)
    val validReadingStatusItems get() = ReaderStatusItems.valid(readingStatusItems)
    val resolvedReadingStatusItems get() = readingStatusItems.takeIf { validReadingStatusItems } ?: ReaderStatusItems.defaults
    val visibleReadingStatusItems get() = if(showReadingStatusBar) resolvedReadingStatusItems.filter { it != "pages" || staticPagination } else emptyList()
    val activeTextReplacements get() = textReplacements.takeIf { textReplacementsEnabled && validTextReplacements }.orEmpty()
    val resolvedToolbarTransparency get() = if(toolbarTransparency.isFinite()) toolbarTransparency.coerceIn(0f, 1f) else if(eInkMode) 0f else .25f
    val validToolbarTransparency get() = toolbarTransparency in 0f..1f &&
        listOfNotNull(beforeEInk?.toolbarTransparency, eInkPreferences?.toolbarTransparency).all { it in 0f..1f }
    /** 导入校验同时覆盖休眠的模式，避免切换后才发现非法配置。 */
    fun modeConfigurations(): List<ReaderSettings> = listOf(this) +
        listOfNotNull(beforeEInk, eInkPreferences).map { withPagingState(it).copy(beforeEInk = null, eInkPreferences = null) }
    // 兼容黑白模式尚未成为主题选项之前保存的设置。
    val resolvedTheme get() = if(monochrome) "monochrome" else theme
    val resolvedCustomColors get() = customColors.takeIf { it.valid } ?: ReaderCustomColors()
    /** 解析后的点击分区方案；空值或损坏内容回退到默认四向翻页，保证阅读器始终可翻页。 */
    val resolvedTapZoneMap get() = if (tapZoneMap.isBlank()) cc.novelia.app.reader.ReaderTapZoneMap.Default
        else cc.novelia.app.reader.ReaderTapZoneMap.decode(tapZoneMap)
    fun withTheme(selected: String) = copy(theme = selected, monochrome = false)
    val staticPagination get() = paginationMode == "auto"
    fun withPaginationMode(selected: String): ReaderSettings {
        // 从滚动阅读首次切换分页时提供可用手势；之后切换模式保留已有选择。
        val needsGestures = selected == "auto" && !staticPagination && !scrollPageTurn && !horizontalPageTurn && !showPageButtons && !tapPageTurn
        return copy(paginationMode = selected, scrollPageTurn = scrollPageTurn || needsGestures,
            horizontalPageTurn = horizontalPageTurn || needsGestures)
    }
    private fun pagingState() = ReaderPagingState(
        mode = mode,
        engines = engines,
        parallel = parallel,
        fontSize = fontSize,
        lineHeight = lineHeight,
        weight = weight,
        width = width,
        indent = indent,
        theme = theme,
        secondaryAlpha = secondaryAlpha,
        underline = underline,
        keepScreenOn = keepScreenOn,
        volumeKeys = volumeKeys,
        paged = paged,
        monochrome = monochrome,
        scrollPageTurn = scrollPageTurn,
        horizontalPageTurn = horizontalPageTurn,
        brightness = brightness,
        speechRate = speechRate,
        speechMinutes = speechMinutes,
        traditional = traditional,
        speechLanguage = speechLanguage,
        paginationMode = paginationMode,
        showPageButtons = showPageButtons,
        toolbarTransparency = toolbarTransparency,
        prefetchChapters = prefetchChapters,
        prefetchWifiOnly = prefetchWifiOnly,
        paragraphSpacing = paragraphSpacing,
        showScrollPageButtons = showScrollPageButtons,
        showProgressBar = showProgressBar,
        showReadingStatusBar = showReadingStatusBar,
        speechContinueChapters = speechContinueChapters,
        speechNetworkContinuation = speechNetworkContinuation,
        showEInkScreenButtons = showEInkScreenButtons,
        hideStatusBar = hideStatusBar,
        tapPageTurn = tapPageTurn,
        tapZoneMap = tapZoneMap,
        customColors = customColors,
        fontFamily = fontFamily,
        customFont = customFont,
        horizontalMargin = horizontalMargin,
        verticalMargin = verticalMargin,
        showWideToc = showWideToc,
        orientation = orientation,
        autoReadSpeed = autoReadSpeed,
        autoReadIntervalSeconds = autoReadIntervalSeconds,
        textReplacements = textReplacements,
        textReplacementsEnabled = textReplacementsEnabled,
        readingStatusItems = readingStatusItems,
        showEpubRuby = showEpubRuby,
        epubFootnotePopup = epubFootnotePopup,
        showEpubStyles = showEpubStyles
    )
    private fun withPagingState(state: ReaderPagingState) = copy(
        mode = state.mode ?: mode,
        engines = state.engines ?: engines,
        parallel = state.parallel ?: parallel,
        fontSize = state.fontSize ?: fontSize,
        lineHeight = state.lineHeight ?: lineHeight,
        weight = state.weight ?: weight,
        width = state.width ?: width,
        indent = state.indent ?: indent,
        theme = state.theme ?: theme,
        secondaryAlpha = state.secondaryAlpha ?: secondaryAlpha,
        underline = state.underline ?: underline,
        keepScreenOn = state.keepScreenOn ?: keepScreenOn,
        volumeKeys = state.volumeKeys,
        paged = state.paged ?: paged,
        monochrome = state.monochrome ?: monochrome,
        scrollPageTurn = state.scrollPageTurn,
        horizontalPageTurn = state.horizontalPageTurn,
        brightness = state.brightness ?: brightness,
        speechRate = state.speechRate ?: speechRate,
        speechMinutes = state.speechMinutes ?: speechMinutes,
        traditional = state.traditional ?: traditional,
        speechLanguage = state.speechLanguage ?: speechLanguage,
        paginationMode = state.paginationMode,
        showPageButtons = state.showPageButtons,
        toolbarTransparency = state.toolbarTransparency ?: toolbarTransparency,
        prefetchChapters = state.prefetchChapters ?: prefetchChapters,
        prefetchWifiOnly = state.prefetchWifiOnly ?: prefetchWifiOnly,
        paragraphSpacing = state.paragraphSpacing ?: paragraphSpacing,
        showScrollPageButtons = state.showScrollPageButtons ?: showScrollPageButtons,
        showProgressBar = state.showProgressBar ?: showProgressBar,
        showReadingStatusBar = state.showReadingStatusBar ?: showReadingStatusBar,
        speechContinueChapters = state.speechContinueChapters ?: speechContinueChapters,
        speechNetworkContinuation = state.speechNetworkContinuation ?: speechNetworkContinuation,
        showEInkScreenButtons = state.showEInkScreenButtons ?: showEInkScreenButtons,
        hideStatusBar = state.hideStatusBar ?: hideStatusBar,
        tapPageTurn = state.tapPageTurn ?: tapPageTurn,
        tapZoneMap = state.tapZoneMap ?: tapZoneMap,
        customColors = state.customColors ?: customColors,
        fontFamily = state.fontFamily ?: fontFamily,
        customFont = if(state.fontFamily != null) state.customFont else customFont,
        horizontalMargin = state.horizontalMargin ?: horizontalMargin,
        verticalMargin = state.verticalMargin ?: verticalMargin,
        showWideToc = state.showWideToc ?: showWideToc,
        orientation = state.orientation ?: orientation,
        autoReadSpeed = state.autoReadSpeed ?: autoReadSpeed,
        autoReadIntervalSeconds = state.autoReadIntervalSeconds ?: autoReadIntervalSeconds,
        textReplacements = state.textReplacements ?: textReplacements,
        textReplacementsEnabled = state.textReplacementsEnabled ?: textReplacementsEnabled,
        readingStatusItems = state.readingStatusItems ?: readingStatusItems,
        showEpubRuby = state.showEpubRuby ?: showEpubRuby,
        epubFootnotePopup = state.epubFootnotePopup ?: epubFootnotePopup,
        showEpubStyles = state.showEpubStyles ?: showEpubStyles
    )
    /**
     * 切换电子纸时分别记住两种模式的完整阅读配置。
     * 首次开启继承当前配置并应用电子纸默认值；之后恢复各自的快照。
     */
    fun withEInkMode(enabled: Boolean): ReaderSettings {
        if(enabled == eInkMode) return this
        return if(enabled) {
            val target = eInkPreferences ?: ReaderPagingState(paginationMode = "auto", scrollPageTurn = true,
                horizontalPageTurn = true, showPageButtons = true, volumeKeys = true)
            withPagingState(target).copy(eInkMode = true, beforeEInk = pagingState(),
                toolbarTransparency = target.toolbarTransparency ?: 0f)
        } else {
            // 旧版没有记录切换前模式，回退到原来的滚动阅读默认设置。
            val target = beforeEInk ?: ReaderPagingState(showPageButtons = paged, toolbarTransparency = .25f)
            withPagingState(target).copy(eInkMode = false, beforeEInk = null, eInkPreferences = pagingState(),
                toolbarTransparency = target.toolbarTransparency ?: resolvedToolbarTransparency)
        }
    }
}
