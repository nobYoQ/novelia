package cc.novelia.app.data.sync

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.network.ApiException
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 前台操作和后台重放共用的云端写入协调器，由应用级 API 持有，Activity 重建不会另开写入器。
 * 按“账号 + 资源”加锁，同一书目的收藏、移动和取消依次执行，不同资源可以独立推进。
 * 持久队列由调用方读写，本类只维护资源锁和运行中标记，不自行访问磁盘。
 */
class CloudMutationQueue {
    private val running = MutableStateFlow<Map<String, PendingAction>>(emptyMap())
    /** 仅保存于运行时，进程停止后不得恢复过期的同步中指示。 */
    val inFlight = running.asStateFlow()
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val resources = mutableMapOf<Pair<String, String>, Entry>()

    private suspend fun executeTracked(action: PendingAction, execute: suspend (PendingAction) -> Unit) {
        running.update { it + (action.id to action) }
        try { execute(action) } finally { running.update { it - action.id } }
    }

    private suspend fun <T> ordered(action: PendingAction, block: suspend () -> T): T {
        val key = action.account to resource(action.path)
        val entry = synchronized(resources) { resources.getOrPut(key) { Entry() }.also { it.users++ } }
        try { return entry.mutex.withLock { block() } }
        finally { synchronized(resources) { if (--entry.users == 0) resources.remove(key) } }
    }

    /**
     * 先记录最新意图再发送；相同资源的旧意图被替换，取消或断网后仍可重放最新状态。
     * 只有 PUT/DELETE 进入持久队列，调用方仍须确保具体接口具有可重复执行的语义。
     * 返回 true 表示发生可重试失败且意图已保留，false 表示发送成功；权限等错误直接抛出。
     * updatePending 只要求原子修改本地状态，此方法不等待每次修改落盘。
     */
    suspend fun submit(action: PendingAction, updatePending: ((List<PendingAction>) -> List<PendingAction>) -> Unit,
        validate: () -> Unit = {}, onQueuedFailure: (IOException) -> Unit = {}, execute: suspend (PendingAction) -> Unit): Boolean = ordered(action) {
        validate()
        val queueable = action.method in listOf("PUT", "DELETE")
        // 发送前先记录最新意图；远端成功后发生取消时，不应留下
        // 更旧的排队值，保留本次幂等写入以便安全重放。
        if (queueable) updatePending { pending -> pending.filterNot { sameResource(it, action) } + action }
        val offline = try { executeTracked(action, execute); false }
        catch (error: IOException) {
            if ((error is ApiException && classifySyncFailure(error) != SyncFailure.RETRY) || !queueable) {
                if (queueable) updatePending { pending -> pending.filterNot { it.id == action.id } }
                throw error
            }
            onQueuedFailure(error)
            true
        }
        if (!offline) updatePending { pending -> pending.filterNot { sameResource(it, action) } }
        offline
    }

    suspend fun replay(account: String, readPending: () -> List<PendingAction>,
        updatePending: ((List<PendingAction>) -> List<PendingAction>) -> Unit,
        execute: suspend (PendingAction) -> Unit) {
        for (action in readPending().filter { it.account == account }) ordered(action) {
            // 较新的在线写入或另一轮重放可能已经替代本次捕获的队列项。
            if (readPending().none { it.id == action.id }) return@ordered
            executeTracked(action, execute)
            updatePending { pending -> pending.filterNot { it.id == action.id } }
        }
    }

    /**
     * 每轮最多处理 100 项，跳过需要手动处理的操作。单个资源被拒绝不妨碍其他资源，
     * 但断网、限流或认证失效会结束本轮，避免持续请求。成功项按操作 ID 删除，
     * 取得资源锁后再次检查队列，防止重放已被前台新操作替换的旧意图。
     */
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
                    executeTracked(action, execute)
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
            if(requiresLogin || retry) break // 遵守服务器退避要求，避免继续集中发送排队请求。
        }
        return CloudReplayResult(completed, failures, blocked, retry, requiresLogin)
    }

    private fun sameResource(left: PendingAction, right: PendingAction) = left.account == right.account && resource(left.path) == resource(right.path)
    private fun resource(path: String): String {
        val segments = path.trim('/').split('/')
        // 每本书只有一个当前云端收藏夹，移动收藏夹也使用同一串行排序键。
        return if (segments.size >= 4 && segments[0] == "user" && segments[1] in listOf("favored-web", "favored-wenku"))
            (segments.take(2) + segments.drop(3)).joinToString("/") else segments.joinToString("/")
    }
}
