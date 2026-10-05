package cc.novelia.app.ui.notes

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Note

internal data class NotePresentation(val note: Note, val bookTitle: String, val chapterTitle: String)

internal fun presentNotes(state: LibraryState, query: String = "", bookKey: String? = null): List<NotePresentation> {
    val books = state.books.associateBy { it.book.ref.key }
    val text = query.trim()
    return state.notes.asSequence().filter { (it.bookmarked || it.text.isNotBlank()) && (bookKey == null || it.key == bookKey) }.map { note ->
        val book = note.bookTitle.ifBlank { books[note.key]?.book?.title ?: "未命名作品" }
        val chapter = note.chapterTitle.ifBlank {
            state.positions[note.key]?.takeIf { it.chapterId == note.chapterId }?.title?.ifBlank { null }
                ?: "章节 ${note.chapterId}"
        }
        NotePresentation(note, book, chapter)
    }.filter { text.isBlank() || listOf(it.bookTitle, it.chapterTitle, it.note.quote, it.note.text).any { value -> value.contains(text, ignoreCase = true) } }
        .sortedByDescending { it.note.createdAt }.toList()
}
