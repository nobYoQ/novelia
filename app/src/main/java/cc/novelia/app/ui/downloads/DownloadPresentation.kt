package cc.novelia.app.ui.downloads


internal fun downloadRecoveryLabel(status: String): String = when(status) {
    "需要登录" -> "登录后继续"
    "已暂停" -> "重新开始"
    else -> "重试"
}
