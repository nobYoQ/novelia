package cc.novelia.app.data.webdav

import kotlinx.serialization.Serializable

enum class BootstrapPreference { LOCAL_SETTINGS, REMOTE_SETTINGS }

data class WebDavPreviewItem(val domain: SyncDomain, val localCount: Int, val remoteCount: Int, val conflicts: Int)
data class WebDavPreview(val datasetId: String?, val existing: Boolean, val items: List<WebDavPreviewItem>)

@Serializable data class WebDavDomainStatus(val lastSuccessAt: Long = 0, val error: String? = null, val pending: Boolean = false, val retryable: Boolean = false, val pendingCount: Int = 0)
data class WebDavSyncStatus(
    val running: Boolean = false,
    val lastAttemptAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val error: String? = null,
    val domains: Map<SyncDomain, WebDavDomainStatus> = emptyMap(),
    val conflicts: List<SyncConflict> = emptyList(),
    val recovery: WebDavRecovery? = null,
)

@Serializable internal data class WebDavManifest(
    val format: String = "novelia-webdav",
    val schemaVersion: Int = 1,
    val datasetId: String,
)

@Serializable internal data class WebDavCachedDomain(
    val etag: String,
    val document: SyncDocument,
    val lastSuccessAt: Long = 0,
)

@Serializable internal data class WebDavRuntimeState(
    val datasetId: String? = null,
    val joined: Set<SyncDomain> = emptySet(),
    val cached: Map<SyncDomain, WebDavCachedDomain> = emptyMap(),
    val syncDevicePreferences: Boolean = false,
)
