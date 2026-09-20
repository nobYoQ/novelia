package cc.novelia.app.data.sync

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.PendingAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Waiting for another sync must never rebind a user's intent to a newer login. */
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

/** A stale confirmation cannot change the queue, even after signing back into the same account. */
internal fun LibraryState.removePendingForSession(action: PendingAction, owner: SessionBinding, current: SessionBinding): LibraryState {
    if(owner != current || owner.account != action.account) return this
    return updateCloudPending { items -> items.filterNot { it.id == action.id && it.account == owner.account } }
}
