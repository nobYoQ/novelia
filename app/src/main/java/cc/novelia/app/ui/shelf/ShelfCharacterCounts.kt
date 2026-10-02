package cc.novelia.app.ui.shelf

import androidx.compose.runtime.*
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.ui.discover.enrichAuthors
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Stable internal class ShelfCharacterCounts {
    var values by mutableStateOf<Map<String, Long?>>(emptyMap())
    var loading by mutableStateOf(false)
    var request by mutableIntStateOf(0)
    fun count(book: BookCard) = book.totalCharacters ?: values[book.ref.key]
    fun loadMore(books: List<BookCard>) {
        if(loading) return
        if(books.none { count(it) == null && it.ref.key !in values }) values = values.filterValues { it != null }
        request++
    }
}

/** 仅在用户启用字数筛选后补齐本地网络收藏，每次最多二十本，可继续补齐未知项。 */
@Composable internal fun rememberShelfCharacterCounts(c: AppController, books: List<BookCard>, enabled: Boolean,
    account: String?, cacheGeneration: Long): ShelfCharacterCounts {
    val state = remember(account, cacheGeneration) { ShelfCharacterCounts() }
    val latestBooks by rememberUpdatedState(books)
    val keys = books.map { it.ref.key }.sorted()
    LaunchedEffect(enabled, keys, account, cacheGeneration, state.request) {
        if(!enabled) return@LaunchedEffect
        val pending = latestBooks.filter { state.count(it) == null && it.ref.key !in state.values }.take(20)
        if(pending.isEmpty()) return@LaunchedEffect
        state.loading = true
        try {
            val loaded = enrichAuthors(pending, c, emptySet(), characters = true)
            currentCoroutineContext().ensureActive()
            state.values = state.values + loaded.associate { it.ref.key to it.totalCharacters }
        } finally { state.loading = false }
    }
    return state
}
