package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderPagingState
import cc.novelia.app.data.model.ReaderSettings

internal data class ReaderSettingDifference(val label: String, val book: String, val default: String)

/** 比较实际生效的设置，兼容旧主题/分页字段，避免把内部迁移字段显示为用户差异。 */
internal fun readerSettingsDifferences(book: ReaderSettings, defaults: ReaderSettings): List<ReaderSettingDifference> {
    fun ReaderSettings.values(): Map<String, String> = linkedMapOf(
        "显示语言" to when(mode) { "jp" -> "日文"; "zh-jp" -> "中日"; "jp-zh" -> "日中"; else -> "中文" },
        "译文优先顺序" to engines.joinToString(" → "), "并列展示译文" to parallel.label(),
        "字号" to "$fontSize sp", "行距" to "$lineHeight 倍", "段距" to "$resolvedParagraphSpacing dp",
        "加粗文字" to weight.label(), "内容宽度" to "$width dp", "首行缩进" to indent.label(),
        "阅读主题" to when(resolvedTheme) { "paper" -> "纸张"; "light" -> "浅色"; "dark" -> "深色"; "monochrome" -> "黑白"; "custom" -> "自定义"; else -> "跟随应用" },
        "自定义配色" to if(resolvedTheme == "custom") resolvedCustomColors.let { "文字 #${"%06X".format(it.text)}；背景 #${"%06X".format(it.background)}；工具栏 #${"%06X".format(it.toolbar)}" } else "未启用",
        "辅文本不透明度" to "${(secondaryAlpha * 100).toInt()}%", "辅文本下划线" to underline.label(),
        "屏幕常亮" to keepScreenOn.label(), "音量键翻页" to volumeKeys.label(), "电子纸阅读模式" to eInkMode.label(),
        "分页模式" to if(staticPagination) "自动分页" else "连续滚动", "滚动翻页" to scrollPageTurn.label(),
        "左右翻页" to horizontalPageTurn.label(), "点击区域翻页" to tapPageTurn.label(), "翻页按钮" to showPageButtons.label(),
        "章节末尾按钮" to showScrollPageButtons.label(), "阅读进度条" to showProgressBar.label(),
        "列表与面板翻屏按钮" to showEInkScreenButtons.label(), "阅读时隐藏状态栏" to hideStatusBar.label(),
        "屏幕亮度" to if(brightness < 0) "跟随系统" else "${(brightness * 100).toInt()}%",
        "工具栏透明度" to "${(resolvedToolbarTransparency * 100).toInt()}%", "繁体显示" to traditional.label(),
        "朗读速度" to "$speechRate×", "朗读定时停止" to "$speechMinutes 分钟",
        "朗读语言" to when(speechLanguage) { "jp" -> "日文"; "zh" -> "中文"; else -> "随显示模式" },
        "连续听书" to speechContinueChapters.label(), "联网续章" to speechNetworkContinuation.label(),
        "自动预读后续章节" to "$prefetchChapters 章", "仅 Wi-Fi 自动预读" to prefetchWifiOnly.label(),
        "普通模式翻页预设" to beforeEInk.label(), "电子纸翻页预设" to eInkPreferences.label()
    )
    val global = defaults.values()
    return book.values().mapNotNull { (label, value) -> global.getValue(label).takeIf { it != value }?.let { ReaderSettingDifference(label, value, it) } }
}

private fun Boolean.label() = if(this) "开启" else "关闭"
private fun ReaderPagingState?.label(): String = this?.let {
    "${if(it.paginationMode == "auto") "自动分页" else "连续滚动"}；滚动 ${it.scrollPageTurn.label()}；左右 ${it.horizontalPageTurn.label()}；按钮 ${it.showPageButtons.label()}；音量键 ${it.volumeKeys.label()}"
} ?: "未单独保存"
