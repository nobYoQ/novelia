package cc.novelia.app.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import cc.novelia.app.MainActivity

/**
 * 别名只用于无界面的短暂入口，阅读/设置留在 MainActivity 自己的任务中。
 * 否则禁用当前别名时系统可能异步移除整个任务，连刚从新图标返回的页面也会被关闭。
 */
class LauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        })
        finish()
    }
}
