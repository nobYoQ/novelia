package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class SquareCornerTarget(val label: String, val description: String) {
    Covers("小说封面", "书架、发现和详情里的封面裁切"),
    Cards("普通卡片", "内容卡片和界面容器"),
    Controls("按钮与输入控件", "按钮、标签、输入框、开关和滑块"),
    Panels("弹窗与底部面板", "对话框、菜单和底部弹层"),
    Navigation("导航与阅读控件", "导航选中标记、阅读进度条等"),
    ;

    companion object {
        val defaults = entries.toSet()
    }
}
