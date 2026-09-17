package cc.novelia.app.ui

import androidx.compose.runtime.*
import cc.novelia.app.data.LocalStore
import cc.novelia.app.data.WenkuDetail
import cc.novelia.app.data.appJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Reads the live editor when leaving, including changes not yet recomposed or debounced. */
internal class DraftPersistence(private val read: () -> String, private val write: (String?) -> Unit) {
    private var completed = false

    fun save() { if (!completed) write(read()) }

    fun submittedSuccessfully(submitted: String) {
        if (read() == submitted) {
            completed = true
            write(null)
        } else save()
    }
}

@Composable internal fun rememberDraftPersistence(store: LocalStore, key: String, read: () -> String): DraftPersistence {
    return androidx.compose.runtime.key(store, key) {
        val latestRead by rememberUpdatedState(read)
        val persistence = remember {
            DraftPersistence({ latestRead() }) { value ->
                store.update { state -> state.copy(drafts = if (value == null) state.drafts - key else state.drafts + (key to value)) }
            }
        }
        DisposableEffect(persistence) { onDispose { persistence.save() } }
        persistence
    }
}

/** The conflict check and request deliberately use the exact same set of editable fields. */
internal fun WenkuDetail.editablePayload(): JsonObject = JsonObject(
    appJson.encodeToJsonElement(WenkuDetail.serializer(), this).jsonObject.filterKeys {
        it in setOf("title", "titleZh", "cover", "authors", "artists", "level", "introduction", "keywords", "volumes")
    }
)
