package cc.novelia.app.ui.book

import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The conflict check and request deliberately use the exact same set of editable fields. */
internal fun WenkuDetail.editablePayload(): JsonObject = JsonObject(
    appJson.encodeToJsonElement(WenkuDetail.serializer(), this).jsonObject.filterKeys {
        it in setOf("title", "titleZh", "cover", "authors", "artists", "level", "introduction", "keywords", "volumes")
    }
)
