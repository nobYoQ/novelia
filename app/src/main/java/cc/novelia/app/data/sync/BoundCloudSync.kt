package cc.novelia.app.data.sync

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.PendingAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 串行化整轮同步，并保留进入等待前捕获的会话绑定。
 * 加锁前后都校验身份：等待另一次同步时可能已经退出或切换账号，不能改绑后继续执行。
 */
internal class BoundCloudSync {
    private val mutex = Mutex()

    suspend fun <T> run(binding: SessionBinding, validate: (SessionBinding) -> Unit, block: suspend () -> T): T {
        validate(binding)
        return mutex.withLock {
            validate(binding)
            block()
        }
    }
}

internal fun pendingForSync(pending: List<PendingAction>, binding: SessionBinding, bookKey: String?): List<PendingAction> =
    pending.filter { it.account == binding.account && (bookKey == null || pendingBookKey(it) == bookKey) }

/** 过期确认不得修改队列，即使退出后重新登录同一账号也须拒绝。 */
internal fun LibraryState.removePendingForSession(action: PendingAction, owner: SessionBinding, current: SessionBinding): LibraryState {
    if(owner != current || owner.account != action.account) return this
    return updateCloudPending { items -> items.filterNot { it.id == action.id && it.account == owner.account } }
}
