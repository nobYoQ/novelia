package cc.novelia.app.data

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by the application API, so activity recreation cannot introduce another writer. */
class CloudMutationQueue {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val resources = mutableMapOf<Pair<String, String>, Entry>()

    private suspend fun <T> ordered(action: PendingAction, block: suspend () -> T): T {
        val key = action.account to resource(action.path)
        val entry = synchronized(resources) { resources.getOrPut(key) { Entry() }.also { it.users++ } }
        try { return entry.mutex.withLock { block() } }
        finally { synchronized(resources) { if (--entry.users == 0) resources.remove(key) } }
    }

    /** Returns true only for a recoverable offline failure. Older writes are superseded atomically. */
    suspend fun submit(action: PendingAction, updatePending: ((List<PendingAction>) -> List<PendingAction>) -> Unit,
        validate: () -> Unit = {}, execute: suspend (PendingAction) -> Unit): Boolean = ordered(action) {
        validate()
        val queueable = action.method in listOf("PUT", "DELETE")
        // Record the newest intent before sending. Cancellation after a remote success must not
        // leave an older queued value behind; replaying this idempotent write is safe instead.
        if (queueable) updatePending { pending -> pending.filterNot { sameResource(it, action) } + action }
        val offline = try { execute(action); false }
        catch (error: IOException) {
            if ((error is ApiException && classifySyncFailure(error) != SyncFailure.RETRY) || !queueable) {
                if (queueable) updatePending { pending -> pending.filterNot { it.id == action.id } }
                throw error
            }
            true
        }
        if (!offline) updatePending { pending -> pending.filterNot { sameResource(it, action) } }
        offline
    }

    suspend fun replay(account: String, readPending: () -> List<PendingAction>,
        updatePending: ((List<PendingAction>) -> List<PendingAction>) -> Unit,
        execute: suspend (PendingAction) -> Unit) {
        for (action in readPending().filter { it.account == account }) ordered(action) {
            // A newer online write or another replay may have superseded this captured queue item.
            if (readPending().none { it.id == action.id }) return@ordered
            execute(action)
            updatePending { pending -> pending.filterNot { it.id == action.id } }
        }
    }

    /** A rejected resource must not prevent independent favorites/history from synchronizing. */
    suspend fun replayEligible(account: String, readPending: () -> List<PendingAction>,
        updatePending: ((List<PendingAction>) -> List<PendingAction>) -> Unit,
        blockedActions: Set<String> = emptySet(), execute: suspend (PendingAction) -> Unit): CloudReplayResult {
        var completed = 0
        var retry = false
        var requiresLogin = false
        val failures = linkedMapOf<String, String>()
        val blocked = mutableSetOf<String>()
        for(action in readPending().filter { it.account == account && it.id !in blockedActions }.take(100)) {
            ordered(action) {
                if(readPending().none { it.id == action.id }) return@ordered
                try {
                    execute(action)
                    updatePending { pending -> pending.filterNot { it.id == action.id } }
                    completed++
                } catch(error: IOException) {
                    if(error is SessionChangedException) throw error
                    failures[action.id] = syncFailureMessage(error)
                    when(classifySyncFailure(error)) {
                        SyncFailure.RETRY -> retry = true
                        SyncFailure.AUTHENTICATION -> requiresLogin = true
                        SyncFailure.BLOCKED -> blocked += action.id
                        SyncFailure.ACCOUNT_CHANGED -> throw error
                    }
                }
            }
            if(requiresLogin || retry) break // Respect server backoff; do not storm it with queued work.
        }
        return CloudReplayResult(completed, failures, blocked, retry, requiresLogin)
    }

    private fun sameResource(left: PendingAction, right: PendingAction) = left.account == right.account && resource(left.path) == resource(right.path)
    private fun resource(path: String): String {
        val segments = path.trim('/').split('/')
        // Favorites have one current folder per book, so folder moves also share one ordering key.
        return if (segments.size >= 4 && segments[0] == "user" && segments[1] in listOf("favored-web", "favored-wenku"))
            (segments.take(2) + segments.drop(3)).joinToString("/") else segments.joinToString("/")
    }
}
