package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class Folder(val id: String = "", val title: String = "")

@Serializable data class CloudFolders(val favoredWeb: List<Folder> = emptyList(), val favoredWenku: List<Folder> = emptyList())

@Serializable data class PendingAction(val id: String, val account: String, val method: String, val path: String, val body: String? = null, val contentType: String = "application/json")
