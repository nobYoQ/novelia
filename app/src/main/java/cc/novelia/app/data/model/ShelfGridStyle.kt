package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable enum class ShelfGridStyle(val label: String) {
    Classic("经典"), FullCover("完整封面"),
}
