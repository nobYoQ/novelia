package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class DownloadEntry(val id: String, val title: String, val fileName: String, val url: String, val status: String = "等待下载", val progress: Int = 0, val error: String? = null, val sourceBook: BookRef? = null, val workId: String? = null,
    val sourceCard: BookCard? = null)

/** 独立于书架和下载任务的来源关联；移出书架或清理下载后仍可定位阅读副本。 */
@Serializable data class DownloadedBookLink(val downloadId: String, val localBook: BookRef,
    val sourceBook: BookRef? = null, val volumeId: String? = null)
