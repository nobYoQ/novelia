package cc.novelia.app.ui.components.documents

import android.net.Uri
import cc.novelia.app.files.importing.importDocumentUri
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cc.novelia.app.files.importing.readDocumentContent

suspend fun importDocument(c: AppController, uri: Uri) = withContext(Dispatchers.IO) {
    val result = importDocumentUri(c.store, uri)
    if(!result.imported) {
        val existing = c.store.state.value.books.firstOrNull { it.book.ref == result.ref }
        c.message(existing?.let { "「${it.book.title}」已在书架中" } ?: "这份文件已在书架中")
    }
    result.imported
}
fun readDocument(c: AppController, uri: Uri): Pair<String, ByteArray> =
    readDocumentContent(c.app.contentResolver, uri)
