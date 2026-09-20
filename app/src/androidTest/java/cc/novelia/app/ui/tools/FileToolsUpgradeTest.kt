package cc.novelia.app.ui.tools

import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileToolsUpgradeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun removedImageToolKeepsOldCorrectedTextAvailableInOrdinaryTextTools() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("本地文件").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val original = app.store.state.value.drafts["tool:local-ocr"]
        val corrected = "升级前校对的内容。\n\n这一段需要保留。"
        try {
            compose.runOnIdle { app.store.update { it.copy(drafts = it.drafts + ("tool:local-ocr" to corrected)) } }
            compose.onNodeWithText("我的").performClick()
            compose.onNodeWithText("文件工具").performScrollTo().performClick()
            compose.onNodeWithText("本地图片识字").assertDoesNotExist()
            compose.onNodeWithText("下载模型", substring = true).assertDoesNotExist()
            compose.onNodeWithText("取回上次校对文本").performClick()
            compose.onNodeWithText("粘贴或编辑文本").assertTextContains(corrected)
            compose.onNodeWithText("开始处理").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("结果预览").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("导出文件").performScrollTo().assertIsEnabled()
            assertEquals(corrected, app.store.state.value.drafts["tool:local-ocr"])
        } finally {
            compose.runOnIdle { app.store.update { it.copy(drafts = if (original == null) it.drafts - "tool:local-ocr" else it.drafts + ("tool:local-ocr" to original)) } }
            runBlocking { app.store.flush() }
        }
    }
}
