package cc.novelia.app.ui.account

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProfileSecondaryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun secondaryPagesKeepTheirActionsAndBothSyncEntrancesShareOnePage() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("我的").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as NoveliaApplication
        val original = app.store.state.value
        try {
            compose.runOnIdle { app.store.update { it.copy(theme = "light", reducedMotion = true, reader = it.reader.copy(eInkMode = false)) } }
            compose.onNodeWithText("我的").performClick()
            val pages = listOf(
                Triple("history", "阅读历史", "历史来源"),
                Triple("downloads", "下载管理", "批量操作"),
                Triple("updates", "书架更新", "检查更新"),
                Triple("notes", "书签与笔记", "全部书籍"),
                Triple("tools", "文件工具", "个人术语表"),
                Triple("blocked", "屏蔽管理", "添加用户"),
                Triple("backup", "阅读资料备份", "导出备份"),
                Triple("about", "帮助与关于", "Novelia"),
                Triple("settings", "设置", "阅读与朗读"),
                Triple("sync", "网络与同步", "原站账号同步"),
            )
            for((route, title, action) in pages) {
                compose.onNodeWithTag("profile-list").performScrollToNode(hasTestTag("profile-$route"))
                compose.onNodeWithTag("profile-$route").performClick()
                compose.onNodeWithText(title).assertIsDisplayed()
                if(route == "updates") compose.onNodeWithContentDescription(action).assertIsDisplayed()
                else compose.onNodeWithText(action).assertIsDisplayed()
                saveTestScreenshot("profile-secondary-$route.png")
                if(route == "sync") {
                    compose.runOnIdle { app.store.update { it.copy(theme = "dark") } }
                    compose.waitForIdle()
                    saveTestScreenshot("profile-secondary-sync-dark.png")
                    compose.runOnIdle { app.store.update { it.copy(theme = "light") } }
                    compose.waitForIdle()
                    option("联网自动同步").performClick()
                    compose.runOnIdle { assertEquals(!original.autoSync, app.store.state.value.autoSync) }
                    option("WebDAV 多设备同步").performClick()
                    compose.onNodeWithText("多设备同步").assertIsDisplayed()
                    compose.onNodeWithContentDescription("返回").performClick()
                    option("书源线路").assertHasClickAction()
                    option("网络诊断与日志").assertHasClickAction()
                }
                compose.onNodeWithContentDescription("返回").performClick()
                compose.onNodeWithTag("profile-list").assertExists()
            }
            compose.onNodeWithTag("profile-list").performScrollToNode(hasTestTag("profile-settings"))
            compose.onNodeWithTag("profile-settings").performClick()
            option("网络与同步").performClick()
            compose.onNodeWithText("原站账号同步").assertIsDisplayed()
            option("联网自动同步").assert(if(original.autoSync) isOff() else isOn()).performClick()
            compose.runOnIdle { assertEquals(original.autoSync, app.store.state.value.autoSync) }
            compose.onNodeWithContentDescription("返回").performClick()
            option("阅读与朗读").assertIsDisplayed()
        } finally {
            compose.runOnIdle { app.store.update { it.copy(theme = original.theme, reducedMotion = original.reducedMotion,
                autoSync = original.autoSync, reader = it.reader.copy(eInkMode = original.reader.eInkMode)) } }
        }
    }

    private fun option(title: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        return compose.onNodeWithText(title)
    }
}
