package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class Page<T>(val pageNumber: Int = 0, val items: List<T> = emptyList())
