package cc.novelia.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "cc.novelia.app"

private fun MacrobenchmarkScope.waitForShelf() {
    assertTrue("Shelf did not become available", device.wait(Until.hasObject(By.text("本地文件")), 15_000))
}

private fun MacrobenchmarkScope.openAppearance() {
    device.findObject(By.text("我的")).click()
    var appearance = device.wait(Until.findObject(By.text("阅读与外观")), 1000)
    repeat(8) {
        if(appearance == null) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 20)
            appearance = device.wait(Until.findObject(By.text("阅读与外观")), 750)
        }
    }
    checkNotNull(appearance) { "Reading appearance settings were not reachable" }.click()
    assertTrue(device.wait(Until.hasObject(By.text("应用主题")), 5000))
}

@RunWith(AndroidJUnit4::class)
class ReadingBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStart(): Unit = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
        startupMode = StartupMode.COLD, iterations = 5,
        setupBlock = { pressHome() },
    ) { startActivityAndWait(); waitForShelf() }

    @Test fun shelfAndSettings(): Unit = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.UseIfAvailable),
        iterations = 5, setupBlock = { startActivityAndWait(); waitForShelf() },
    ) {
        device.findObject(By.text("本地文件")).click()
        val width = device.displayWidth; val height = device.displayHeight
        repeat(4) { device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 20) }
        openAppearance()
        device.pressBack()
        device.findObject(By.text("书架")).click()
        waitForShelf()
    }
}

@RunWith(AndroidJUnit4::class)
class ReadingBaselineProfile {
    @get:Rule val profile = BaselineProfileRule()
    @Test fun startup(): Unit = profile.collect(PACKAGE, includeInStartupProfile = true) {
        pressHome(); startActivityAndWait(); waitForShelf()
    }

    // 导航流程属于通用基线配置，不纳入启动 dex 布局。
    @Test fun localNavigation(): Unit = profile.collect(PACKAGE, includeInStartupProfile = false) {
        startActivityAndWait(); waitForShelf()
        device.findObject(By.text("本地文件")).click()
        openAppearance()
    }
}
