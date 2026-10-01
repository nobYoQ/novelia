package cc.novelia.app.ui.components

import androidx.compose.runtime.*
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.CancellationException

@Composable internal fun rememberCloudBookMetadata(c: AppController, book: BookCard, account: String?, refreshKey: Any?): BookCard {
    return rememberCloudBookMetadata(book, account, refreshKey) { c.refreshCloudReading(it) }
}

@Composable internal fun rememberCloudBookMetadata(book: BookCard, account: String?, refreshKey: Any?, load: suspend (BookCard) -> BookCard): BookCard {
    var resolved by remember(book, account, refreshKey) { mutableStateOf(book) }
    LaunchedEffect(book, account, refreshKey) {
        if(account == null || book.ref.isWenku || book.ref.isLocal) return@LaunchedEffect
        try {
            val loaded = load(book)
            if(loaded.cloudReading?.account == account) resolved = loaded
        } catch(e: CancellationException) {
            throw e
        } catch(_: Exception) {
            // 离线时保留已知元数据，尚未解析的云端位置不得显示成未读。
        }
    }
    return resolved
}
