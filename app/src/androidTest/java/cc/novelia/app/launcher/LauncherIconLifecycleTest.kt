package cc.novelia.app.launcher

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import org.junit.Assert.*
import org.junit.Test

/** 会切换测试安装包的真实桌面入口，只在专用设备/模拟器运行。 */
class LauncherIconLifecycleTest {
    @Suppress("DEPRECATION")
    @Test fun launcherColdStartAndImmediateReentryUseStableMainTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NoveliaApplication
        val manager = app.launcherIcons
        await { manager.state.value.ready }
        val original = manager.state.value.selectedId
        val activityManager = app.getSystemService(ActivityManager::class.java)
        activityManager.appTasks.forEach { it.finishAndRemoveTask() }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var main: MainActivity? = null
        fun launch(id: String) = app.startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, manager.state.value.icons.first { it.id == id }.component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        fun home() { instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
        fun resumed(): Boolean {
            var value = false
            instrumentation.runOnMainSync { value = main?.lifecycle?.currentState == Lifecycle.State.RESUMED }
            return value
        }
        try {
            launch(manager.state.value.current!!.id)
            main = instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? MainActivity
            assertNotNull("The launcher must open MainActivity", main)
            await { resumed() }
            val task = activityManager.appTasks.single { it.taskInfo.id == main!!.taskId }
            assertEquals(MainActivity::class.java.name, task.taskInfo.baseIntent.component?.className)
            val alternative = manager.state.value.icons.first { it.id !in manager.state.value.enabledIds && !it.hidden }.id
            instrumentation.runOnMainSync { manager.select(alternative) }
            await { manager.state.value.selectedId == alternative && !manager.state.value.saving }
            home()
            await { !manager.state.value.pending }
            launch(alternative)
            await { resumed() }
            // 包管理器的桌面变更广播可能稍后到达，不能只检查 startActivity 的瞬间。
            SystemClock.sleep(1_500)
            assertTrue("Changing the launcher must not remove the resumed reading task", resumed())
            assertEquals(MainActivity::class.java.name, task.taskInfo.baseIntent.component?.className)
        } finally {
            instrumentation.runOnMainSync { manager.select(original) }
            await { manager.state.value.selectedId == original && !manager.state.value.saving }
            home()
            await { !manager.state.value.pending }
            instrumentation.runOnMainSync { main?.finishAndRemoveTask() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun foregroundAndRecreationKeepIconUntilProcessStopsThenDefaultCanBeRestored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as NoveliaApplication
        val manager = app.launcherIcons
        await { manager.state.value.ready }
        val original = manager.state.value.selectedId
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                val active = manager.state.value.current!!.id
                val alternative = manager.state.value.icons.first { it.id != active && !it.hidden }.id
                scenario.onActivity { manager.select(alternative) }
                await { manager.state.value.selectedId == alternative && !manager.state.value.saving }
                assertEquals(setOf(active), manager.state.value.enabledIds)
                assertTrue(manager.state.value.pending)
                scenario.recreate()
                SystemClock.sleep(1_000)
                assertEquals(setOf(active), manager.state.value.enabledIds)
                scenario.moveToState(Lifecycle.State.STARTED)
                SystemClock.sleep(1_000)
                assertEquals(setOf(active), manager.state.value.enabledIds)
                scenario.moveToState(Lifecycle.State.CREATED)
                await { !manager.state.value.pending }
                assertEquals(setOf(alternative), launcherIds(app))
                assertMainActivityStillWorks(app)

                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { manager.select(DEFAULT_LAUNCHER_ICON) }
                await { manager.state.value.selectedId == DEFAULT_LAUNCHER_ICON && !manager.state.value.saving }
                assertEquals(setOf(alternative), launcherIds(app))
                scenario.moveToState(Lifecycle.State.CREATED)
                await { launcherIds(app) == setOf(DEFAULT_LAUNCHER_ICON) }
                assertMainActivityStillWorks(app)
            } finally {
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { manager.select(original) }
                await { manager.state.value.selectedId == original && !manager.state.value.saving }
                scenario.moveToState(Lifecycle.State.CREATED)
                await { !manager.state.value.pending }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launcherIds(app: NoveliaApplication): Set<String> = app.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0
    ).mapTo(mutableSetOf()) { it.activityInfo.name.substringAfterLast("Icon_") }

    private fun assertMainActivityStillWorks(app: NoveliaApplication) {
        val pm = app.packageManager
        val setting = pm.getComponentEnabledSetting(ComponentName(app, MainActivity::class.java))
        assertTrue(setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://n.novelia.cc/novel/syosetu/n1234"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, pm.resolveActivity(link, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name)
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, pm.resolveActivity(share, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name)
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Timed out waiting for launcher icon state", condition())
    }
}
