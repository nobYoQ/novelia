package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class DownloadEntry(val id: String, val title: String, val fileName: String, val url: String, val status: String = "等待下载", val progress: Int = 0, val error: String? = null, val sourceBook: BookRef? = null, val workId: String? = null,
    val sourceCard: BookCard? = null)
