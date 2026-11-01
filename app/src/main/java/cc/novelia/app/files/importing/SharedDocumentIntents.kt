package cc.novelia.app.files.importing

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** 打开方式读取 data；分享附件优先于 EXTRA_TEXT，兼容仅传 ClipData 的文件管理器。 */
internal fun Intent.sharedDocumentUris(): List<Uri> {
    // 只接收文档提供器授权的文件，网页深链继续交给路由处理。
    if (action == Intent.ACTION_VIEW) return listOfNotNull(data?.takeIf { it.scheme == "content" })
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
    val streams = runCatching {
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
    }.getOrDefault(emptyList()).filterIsInstance<Uri>()
    return streams.ifEmpty {
        val clip = clipData
        val items = if(clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        items.ifEmpty { listOfNotNull(data?.takeIf { it.scheme == "content" }) }
    }.distinct()
}
