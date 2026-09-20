package cc.novelia.app.data.sync

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.network.ApiException
import java.io.IOException
import kotlinx.serialization.Serializable

@Serializable data class CloudSyncStatus(
    val lastAttemptAt: Long = 0,
    val lastSuccessAt: Long = 0,
    val failures: Map<String, String> = emptyMap(),
    val blockedActions: Set<String> = emptySet(),
    val requiresLogin: Boolean = false
)

internal enum class SyncFailure { RETRY, AUTHENTICATION, BLOCKED, ACCOUNT_CHANGED }

internal fun classifySyncFailure(error: IOException): SyncFailure = when {
    error is SessionChangedException -> SyncFailure.ACCOUNT_CHANGED
    error is ApiException && error.status == 401 -> SyncFailure.AUTHENTICATION
    error !is ApiException || error.status in listOf(408, 429) || error.status in 500..599 -> SyncFailure.RETRY
    else -> SyncFailure.BLOCKED
}

internal fun syncFailureMessage(error: IOException): String = when(classifySyncFailure(error)) {
    SyncFailure.ACCOUNT_CHANGED -> "账号已变化，原账号的操作仍保留"
    SyncFailure.AUTHENTICATION -> "登录已失效，请重新登录后重试"
    SyncFailure.RETRY -> if(error is ApiException && error.status == 429) "请求较多，稍后自动重试" else "网络或服务暂不可用，将自动重试"
    SyncFailure.BLOCKED -> when((error as ApiException).status) {
        403 -> "当前账号没有权限，请检查后手动重试"
        404 -> "作品或收藏夹已不存在，请检查后重试或移除此操作"
        409 -> "原站资料发生冲突，请检查后手动重试"
        else -> "原站未接受此操作（${error.status}），请检查后手动重试"
    }
}

data class CloudReplayResult(
    val completed: Int = 0,
    val failures: Map<String, String> = emptyMap(),
    val blockedActions: Set<String> = emptySet(),
    val retry: Boolean = false,
    val requiresLogin: Boolean = false
)
