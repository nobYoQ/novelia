package cc.novelia.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** 当前子树需要静态显示，由应用、墨水屏和系统动画策略共同决定。 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** 当前子树启用墨水屏交互策略，除静态显示外还影响手势提交和翻屏方式。 */
val LocalEInkMode = staticCompositionLocalOf { false }

/** 列表与面板的翻屏按钮可独立关闭；滑动、滚轮和硬件翻页键仍可用。 */
val LocalScreenPageButtons = staticCompositionLocalOf { true }
