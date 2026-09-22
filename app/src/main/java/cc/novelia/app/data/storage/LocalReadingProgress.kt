package cc.novelia.app.data.storage

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.projectParagraphs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Older reading records have an anchor but no totals from which the shelf can show progress. */
internal fun LibraryState.localReadingProgressCandidates(): Map<BookRef, Position> = buildMap {
    for(saved in books) {
        val ref = saved.book.ref
        if(!ref.isLocal) continue
        val position = positions[ref.key] ?: continue
        if(position.chapterIndex == null || position.chapterCount == null || position.paragraphCount == null) put(ref, position)
    }
}

/** Read only the catalogue and the current chapter, then publish one guarded batch of metadata. */
internal suspend fun LocalStore.restoreLocalReadingProgress(
    candidates: Map<BookRef, Position> = state.value.localReadingProgressCandidates(),
) = withContext(Dispatchers.IO) {
    if(candidates.isEmpty()) return@withContext
    val restored = resolveLocalReadingProgress(candidates, ::documentIndex, ::documentChapter)
    currentCoroutineContext().ensureActive()
    if(restored.isNotEmpty()) update { it.withRestoredLocalReadingProgress(candidates, restored) }
}

internal suspend fun resolveLocalReadingProgress(
    candidates: Map<BookRef, Position>,
    readIndex: (String) -> LocalDocument,
    readChapter: (String, String) -> LocalChapter,
): Map<BookRef, Position> {
    val job = currentCoroutineContext()
    return buildMap {
        for((ref, position) in candidates) {
            job.ensureActive()
            if(!ref.isLocal) continue
            try {
                val document = readIndex(ref.id)
                job.ensureActive()
                val ordinal = document.chapters.indexOfFirst { it.id == position.chapterId }
                if(ordinal < 0) continue
                val chapter = readChapter(ref.id, position.chapterId)
                job.ensureActive()
                if(chapter.id != position.chapterId) continue
                // Local chapters use their text for both original and translation in AppController.
                // Share the reader's projection so blank lines and illustrations count identically.
                val count = projectParagraphs(Chapter(paragraphs = chapter.paragraphs, youdaoParagraphs = chapter.paragraphs),
                    ReaderSettings()) { job.ensureActive() }.size
                put(ref, position.copy(chapterIndex = ordinal, chapterCount = document.chapters.size, paragraphCount = count))
            } catch(error: CancellationException) {
                throw error
            } catch(_: Exception) {
                // Missing or damaged content cannot justify inventing a progress value.
            }
        }
    }
}

internal fun LibraryState.withRestoredLocalReadingProgress(
    candidates: Map<BookRef, Position>,
    restored: Map<BookRef, Position>,
): LibraryState {
    val existing = books.asSequence().map { it.book.ref }.toSet()
    val eligible = restored.filter { (ref, _) ->
        ref in existing && candidates[ref] != null && positions[ref.key] == candidates[ref]
    }
    return if(eligible.isEmpty()) this else copy(positions = positions + eligible.mapKeys { it.key.key })
}
