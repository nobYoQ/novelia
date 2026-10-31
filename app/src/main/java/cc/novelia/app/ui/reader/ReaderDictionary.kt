package cc.novelia.app.ui.reader

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException

/** 只有用户点击查词才把选文交给其选择的应用，不预先发送正文或读取剪贴板。 */
internal fun lookupReaderText(context: Context, text: String, onMessage: (String) -> Unit) {
    if(text.isBlank() || text.length > 2000) return
    val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
    @Suppress("DEPRECATION")
    val handlers = context.packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.exported }
    if(handlers.isEmpty()) {
        onMessage("未找到支持选文处理的词典或翻译应用，可先复制选文，或安装支持此功能的应用。")
        return
    }
    try { context.startActivity(Intent.createChooser(intent, "选择词典或翻译应用")) }
    catch(_: ActivityNotFoundException) { onMessage("词典或翻译应用暂时不可用，请重新选择。") }
    catch(_: SecurityException) { onMessage("无法打开所选词典或翻译应用。") }
}
