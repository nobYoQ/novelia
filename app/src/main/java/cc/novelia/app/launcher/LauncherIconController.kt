package cc.novelia.app.launcher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val DEFAULT_LAUNCHER_ICON = "default"

internal data class LauncherIcon(
    val id: String,
    val title: String,
    val drawable: Int,
    val component: String,
    val hidden: Boolean = false,
    val splashTheme: Int = 0
)

internal data class LauncherIconState(
    val icons: List<LauncherIcon> = emptyList(),
    val selectedId: String = DEFAULT_LAUNCHER_ICON,
    val enabledIds: Set<String> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null
) {
    val ready get() = icons.isNotEmpty()
    val pending get() = ready && enabledIds != setOf(selectedId)
    val selected get() = icons.firstOrNull { it.id == selectedId }
    val current get() = icons.firstOrNull { it.id in enabledIds }
}

internal interface LauncherIconBackend {
    fun load(): LauncherIconState
    fun saveSelection(id: String)
    fun enabledIds(): Set<String>
    fun apply(id: String): Set<String>
}

/**
 * 主线程串行处理选择和生命周期；磁盘保存放到 IO。选择先落盘，组件操作仅发生在后台。
 * 即使切换时进程被桌面结束，下次启动仍以系统实际启用的入口判断是否还有待处理的选择。
 */
internal class LauncherIconController(
    private val backend: LauncherIconBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(LauncherIconState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var background = false
    private val initialization = scope.launch {
        try {
            mutableState.value = withContext(io) { backend.load() }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            mutableState.value = LauncherIconState(error = "暂时无法读取桌面图标，请重新打开应用后重试")
        }
    }

    fun onForeground() { background = false }

    fun onBackground() {
        background = true
        scope.launch {
            initialization.join()
            mutex.withLock { applyIfBackground() }
        }
    }

    fun select(id: String) {
        scope.launch {
            initialization.join()
            mutex.withLock {
                val before = mutableState.value
                if (!before.ready || before.icons.none { it.id == id && (!it.hidden || it.id in before.enabledIds) }) return@withLock
                mutableState.value = before.copy(saving = true)
                try {
                    withContext(io) { backend.saveSelection(id) }
                    mutableState.value = before.copy(selectedId = id, error = null)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    mutableState.value = before.copy(error = "图标选择未能保存，请重试")
                    return@withLock
                } finally {
                    mutableState.value = mutableState.value.copy(saving = false)
                }
                // 保存期间可能已经退到后台，仍需完成；若又回到前台则留待下一次。
                applyIfBackground()
            }
        }
    }

    private fun applyIfBackground() {
        val before = mutableState.value
        if (!background || !before.pending) return
        try {
            // 同步的组件事务在主线程执行，避免 IO 线程与 Activity 返回前台并发切换入口。
            val enabled = backend.apply(before.selectedId)
            check(enabled == setOf(before.selectedId))
            mutableState.value = before.copy(enabledIds = enabled, error = null)
        } catch (_: Exception) {
            mutableState.value = before.copy(
                enabledIds = runCatching { backend.enabledIds() }.getOrDefault(before.enabledIds),
                error = "图标切换未完成，下次退到后台时重试"
            )
        }
    }
}

internal data class LauncherIconChange(val id: String, val enabled: Boolean)

/** Android 13 起可以原子更新；旧版严格先开后关，任何一步失败都保留可启动入口。 */
internal fun switchLauncherIconSafely(
    selectedId: String,
    availableIds: Set<String>,
    enabledIds: Set<String>,
    change: (LauncherIconChange) -> Unit,
    atomicChange: ((List<LauncherIconChange>) -> Unit)? = null
) {
    require(selectedId in availableIds)
    val changes = buildList {
        if (selectedId !in enabledIds) add(LauncherIconChange(selectedId, true))
        enabledIds.filter { it != selectedId && it in availableIds }.forEach { add(LauncherIconChange(it, false)) }
    }
    if (changes.isEmpty()) return
    if (atomicChange != null) atomicChange(changes) else changes.forEach(change)
}
