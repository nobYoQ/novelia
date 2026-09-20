package cc.novelia.app.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.importDocumentUri
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun importDocument(c: AppController, uri: Uri) = withContext(Dispatchers.IO) {
    val result = importDocumentUri(c.store, uri)
    if(!result.imported) {
        val existing = c.store.state.value.books.firstOrNull { it.book.ref == result.ref }
        c.message(existing?.let { "「${it.book.title}」已在书架中" } ?: "这份文件已在书架中")
    }
    result.imported
}
fun readDocument(c: AppController, uri: Uri): Pair<String, ByteArray> {
    val resolver = c.app.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "导入文档.txt"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = input.read(buffer); if(n < 0) break; require(output.size() + n <= DocumentTools.MAX_INPUT) { "文件超过 64 MB" }; output.write(buffer, 0, n) }
        output.toByteArray()
    } ?: error("无法读取文件")
    return name to bytes
}
