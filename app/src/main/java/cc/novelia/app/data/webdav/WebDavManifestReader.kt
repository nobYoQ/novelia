package cc.novelia.app.data.webdav

import kotlinx.serialization.json.Json

/** 仍在每轮校验远端标识；304 复用已验证的正文，减少缺少响应头版本时的属性查询。 */
internal class WebDavManifestReader(private val json: Json) {
    private data class Cached(val binding: WebDavConfig, val etag: String, val value: WebDavManifest)
    private var cached: Cached? = null

    suspend fun read(client: WebDavClient, binding: WebDavConfig): WebDavManifest? {
        val previous = cached?.takeIf { it.binding == binding }
        val resource = client.get("manifest.json", previous?.etag) ?: run {
            cached = null
            require(binding.datasetId == null) { "已连接的同步目录标识消失，请检查服务或目录" }
            return null
        }
        if(resource.notModified) return requireNotNull(previous) { "缺少同步目录标识缓存，请重试" }.value
        val etag = WebDavClient.requireStrongEtag(resource.etag)
        val value = json.decodeFromString<WebDavManifest>(checkedWebDavJson(resource.data))
        require(value.format == "novelia-webdav" && value.schemaVersion == 1 && WebDavMerge.validId(value.datasetId)) { "同步目录格式或版本不支持" }
        require(binding.datasetId == null || binding.datasetId == value.datasetId) { "同步目录数据集已变化，请检查目录配置" }
        cached = Cached(binding, etag, value)
        return value
    }
}
