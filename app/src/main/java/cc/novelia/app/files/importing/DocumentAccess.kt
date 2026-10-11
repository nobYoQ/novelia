package cc.novelia.app.files.importing

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import cc.novelia.app.files.DocumentTools

/** 有界读取系统文档；页面负责选择 URI 和展示读取结果。调用方在 IO 调度器执行。 */
internal fun readDocumentContent(resolver: ContentResolver, uri: Uri): Pair<String, ByteArray> {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "导入文档.txt"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = input.read(buffer); if(n < 0) break; require(output.size() + n <= DocumentTools.MAX_INPUT) { "文件超过 64 MB" }; output.write(buffer, 0, n) }
        output.toByteArray()
    } ?: error("无法读取文件")
    return name to bytes
}
