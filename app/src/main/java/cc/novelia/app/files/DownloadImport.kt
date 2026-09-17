package cc.novelia.app.files

import cc.novelia.app.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps a downloaded volume's source association and avoids duplicate reading copies. */
suspend fun importDownloadedDocument(store: LocalStore, entry: DownloadEntry): BookRef = withContext(Dispatchers.IO) {
    val bytes = File(store.downloadsDir, entry.fileName).readBytes()
    val document = DocumentTools.parse(entry.fileName, bytes).copy(name = entry.title)
    val existing = store.state.value.books.firstOrNull { saved ->
        saved.book.ref.isLocal && runCatching { store.document(saved.book.ref.id).sourceHash == document.sourceHash }.getOrDefault(false)
    }
    val ref = existing?.book?.ref ?: BookRef("local", document.id)
    if(existing == null) {
        store.saveDocument(document)
        store.documentSource(document.id, document.format).writeBytes(bytes)
        store.saveBook(BookCard(ref, document.name, cover = document.coverImage?.let { store.documentImage(document.id, it).absolutePath },
            subtitle = "离线文件 · ${document.chapters.size} 章"))
    }
    store.update { state ->
        val parent = entry.sourceBook?.takeIf { it.isWenku && state.books.any { saved -> saved.book.ref == it } }
        val saved = state.books.firstOrNull { it.book.ref == ref }
        // Reimporting must not undo an explicit choice to detach or move an existing copy.
        if(existing == null && parent != null && saved != null) state.withVolumeParent(ref.key, parent.key) else state
    }
    ref
}
