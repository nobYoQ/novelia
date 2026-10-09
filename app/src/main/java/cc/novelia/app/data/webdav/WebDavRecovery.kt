package cc.novelia.app.data.webdav

enum class WebDavRecoveryReason { MISSING_DATASET, REPLACED_DATASET }

/** 只允许用户处理当前绑定的故障，旧弹窗不能解除后来配置的目录。 */
data class WebDavRecovery(val generation: Long, val datasetId: String, val reason: WebDavRecoveryReason) {
    fun matches(binding: WebDavConfig): Boolean = generation == binding.generation && datasetId == binding.datasetId
}

internal class WebDavRecoveryRequiredException(val recovery: WebDavRecovery) : IllegalStateException(
    when(recovery.reason) {
        WebDavRecoveryReason.MISSING_DATASET -> "找不到原来的云端同步资料，请重新连接或检查服务器目录；本机资料已保留"
        WebDavRecoveryReason.REPLACED_DATASET -> "云端同步资料已更换，请重新连接并确认合并内容；本机资料已保留"
    }
)

internal fun WebDavConfig.withoutDatasetBinding(recovery: WebDavRecovery): WebDavConfig {
    if(!recovery.matches(this)) throw WebDavConfigChangedException()
    return copy(datasetId = null, generation = Math.addExact(generation, 1))
}
