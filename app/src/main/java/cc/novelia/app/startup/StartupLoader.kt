package cc.novelia.app.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class StartupStep(val title: String, val description: String) {
    LIBRARY("读取书库", "恢复书架、阅读位置与本地设置"),
    CONNECTION("准备连接", "读取书源线路、同步配置与本地会话"),
    KEYWORDS("整理标签", "加载标签译名与分类，整理书架标签"),
    INTERFACE("准备界面", "初始化图片缓存与页面服务"),
    READY("准备完成", "即将进入书架")
}

internal data class StartupProgress(val step: StartupStep = StartupStep.LIBRARY, val failed: Boolean = false) {
    val ready get() = step == StartupStep.READY
    val completed get() = step.ordinal
}

/** 启动屏只观察轻量进度；磁盘与服务初始化由应用的后台作用域执行。失败后允许重试。 */
internal class StartupLoader {
    private val mutable = MutableStateFlow(StartupProgress())
    val progress = mutable.asStateFlow()
    private val retries = Channel<Unit>(Channel.CONFLATED)

    suspend fun load(steps: List<Pair<StartupStep, suspend () -> Unit>>) {
        while(true) {
            try {
                for((step, action) in steps) {
                    mutable.value = StartupProgress(step)
                    action()
                }
                mutable.value = StartupProgress(StartupStep.READY)
                return
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) {
                mutable.value = mutable.value.copy(failed = true)
                retries.receive()
            }
        }
    }

    fun retry() {
        val current = mutable.value
        if(current.failed && mutable.compareAndSet(current, current.copy(failed = false))) retries.trySend(Unit)
    }
}
