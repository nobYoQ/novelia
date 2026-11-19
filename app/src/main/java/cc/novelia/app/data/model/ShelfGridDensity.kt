package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable enum class ShelfGridDensity(val label: String, val columns: Int?) {
    Auto("自动", null),
    Two("2 列", 2),
    Three("3 列", 3),
    Four("4 列", 4),
}
