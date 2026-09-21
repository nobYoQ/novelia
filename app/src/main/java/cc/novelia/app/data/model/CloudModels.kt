package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class Folder(val id: String = "", val title: String = "")

@Serializable data class CloudFolders(val favoredWeb: List<Folder> = emptyList(), val favoredWenku: List<Folder> = emptyList())

/**
 * 可持久化的云端写入意图。id 标识这一版意图，account 决定谁可以重放，路径标识目标资源。
 * 只保存业务请求内容，认证令牌由执行时的会话提供；不要将无法安全重放的发帖等操作放入队列。
 */
@Serializable data class PendingAction(val id: String, val account: String, val method: String, val path: String, val body: String? = null, val contentType: String = "application/json")
