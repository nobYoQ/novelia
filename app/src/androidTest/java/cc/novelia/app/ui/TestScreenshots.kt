package cc.novelia.app.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** 在专用测试设备中保存合成场景，供界面改动的视觉验收使用。 */
internal fun saveTestScreenshot(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    // 语义树已就绪时，系统合成器仍可能显示上一帧；截图留出短暂的绘制窗口。
    Thread.sleep(300)
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
}
