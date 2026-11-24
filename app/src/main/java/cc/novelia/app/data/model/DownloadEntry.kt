package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class DownloadEntry(val id: String, val title: String, val fileName: String, val url: String, val status: String = QUEUED, val progress: Int = 0, val error: String? = null, val sourceBook: BookRef? = null, val workId: String? = null,
    val sourceCard: BookCard? = null, val receivedBytes: Long = 0) {
    val active: Boolean get() = status == QUEUED || status == WAITING_FOR_SOURCE || status == RECEIVING

    internal fun queued() = copy(status = QUEUED, progress = 0, receivedBytes = 0, error = null)

    companion object {
        // 保留旧记录的状态值。正在接收时 progress 为 -1 表示书源未提供文件总大小。
        const val QUEUED = "等待下载"
        const val WAITING_FOR_SOURCE = "等待书源响应"
        const val RECEIVING = "下载中"
    }
}

/** 独立于书架和下载任务的来源关联；移出书架或清理下载后仍可定位阅读副本。 */
@Serializable data class DownloadedBookLink(val downloadId: String, val localBook: BookRef,
    val sourceBook: BookRef? = null, val volumeId: String? = null)
