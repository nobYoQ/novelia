package cc.novelia.app.ui.community

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import cc.novelia.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 通过真实 Activity 和导航图打开完整详情页，覆盖正文之外的缓存及同步流程。 */
@RunWith(AndroidJUnit4::class)
class ForumRulesNavigationTest {
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<MainActivity>(Intent(
            ApplicationProvider.getApplicationContext<Context>(), MainActivity::class.java
        ).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://forum.novelia.cc/rules")
        })
    ) { rule ->
        lateinit var activity: MainActivity
        rule.scenario.onActivity { activity = it }
        activity
    }

    @Test fun rulesDetailsCanOpenScrollAndReturnWithoutRequiringLogin() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("forum-rules-page").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看处罚记录").assertIsDisplayed()
        compose.onNodeWithText("一般违规行为会受到记分处罚", substring = true).assertExists()
        compose.onNodeWithText("小说 · 上传文库小说").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("更新社区守则").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("书架").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("forum-rules-page").assertDoesNotExist()
    }
}
