package cc.novelia.app.ui.community

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ForumStrikeReadState
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.model.bundledForumCommunityRules
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ForumUpdate20261004UiTest {
    @get:Rule val compose = createComposeRule()
    private val profile = Profile("论坛测试用户", "member", 0, Long.MAX_VALUE, 42)

    @Test fun permissionsRemainReadableInLightAndDarkDoubleFontLayouts() {
        var large by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if(large) 2f else 1f)) {
                NoveliaTheme(if(large) "dark" else "light") {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.width(375.dp).safeDrawingPadding()) { ForumRulesContent {} }
                    }
                }
            }
        }
        for(isLarge in listOf(false, true)) {
            compose.runOnIdle { large = isLarge }
            compose.onNodeWithText("违规处理").performScrollTo().assertIsDisplayed()
            screenshot("rules-${if(isLarge) "dark-large" else "light"}-top")
            val table = bundledForumCommunityRules.blocks.last().table!!
            for(row in table.rows) {
                compose.onNodeWithText("${row.site} · ${row.operation}").performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText("小说 · 上传文库小说").performScrollTo().assertIsDisplayed()
            screenshot("rules-${if(isLarge) "dark-large" else "light"}-permissions")
        }
    }

    @Test fun unreadMenuRefreshesOnOpenAndHidesAccountRemindersAfterLogout() {
        var account by mutableStateOf<Profile?>(profile)
        var unread by mutableStateOf(true)
        var opened = 0
        val actions = mutableListOf<ForumAccountAction>()
        compose.setContent { NoveliaTheme("light") {
            Box(Modifier.padding(top = 48.dp)) { ForumAccountMenu(account, unread, { opened++ }, actions::add) }
        } }
        compose.onNodeWithTag("forum-account-unread", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithContentDescription("有新的处罚记录").assertExists()
        screenshot("account-unread-light")
        compose.onNodeWithText("处罚记录").performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(listOf(ForumAccountAction.STRIKES), actions); unread = false }
        compose.onNodeWithTag("forum-account-unread", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.runOnIdle { account = null; unread = true }
        compose.onNodeWithTag("forum-account-unread", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("forum-account-panel").assertDoesNotExist()
    }

    @Test fun legacyPagesDoNotAcknowledgeAndFailedWritesRetryOnlyTheViewedBoundary() {
        var latest by mutableStateOf<Long?>(null)
        val writes = mutableListOf<Long>()
        var reloads = 0
        compose.setContent { NoveliaTheme("light") {
            ForumStrikeReadConfirmation(latest, "account-1", { id ->
                writes += id
                if(writes.size == 1) throw ApiException(503, "服务暂不可用")
                ForumStrikeReadState(id == 12L)
            }, { reloads++ })
        } }
        compose.runOnIdle { assertTrue(writes.isEmpty()); latest = 12 }
        compose.onNodeWithText("重试标记已读").assertExists().performClick()
        compose.onNodeWithText("还有新处罚记录，请刷新查看。").assertExists()
        compose.onNodeWithText("刷新记录").performClick()
        compose.runOnIdle { assertEquals(listOf(12L, 12L), writes); assertEquals(1, reloads); latest = 15 }
        compose.onNodeWithText("还有新处罚记录，请刷新查看。").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(12L, 12L, 15L), writes) }
    }

    @Test fun lateAcknowledgementsCannotRestoreThePreviousAccountsMessage() {
        var account by mutableStateOf("old")
        val delayed = CompletableDeferred<ForumStrikeReadState>()
        val writes = mutableListOf<String>()
        compose.setContent { NoveliaTheme("light") {
            val captured = account
            ForumStrikeReadConfirmation(12, captured, {
                writes += captured
                if(captured == "old") withContext(NonCancellable) { delayed.await() } else ForumStrikeReadState(false)
            }, {})
        } }
        compose.runOnIdle { assertEquals(listOf("old"), writes); account = "new" }
        compose.runOnIdle { assertEquals(listOf("old", "new"), writes); delayed.complete(ForumStrikeReadState(true)) }
        compose.onNodeWithText("还有新处罚记录，请刷新查看。").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "forum-update-20261004").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
