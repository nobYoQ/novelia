package cc.novelia.app.ui.components.book

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.images.CustomBookCover
import cc.novelia.app.data.model.BookRef

@Composable internal fun rememberCustomBookCover(ref: BookRef): CustomBookCover? {
    val app = LocalContext.current.applicationContext as? NoveliaApplication ?: return null
    return key(app, ref) {
        val cover by remember { app.store.bookCovers.observe(ref) }.collectAsStateWithLifecycle(null)
        cover
    }
}
