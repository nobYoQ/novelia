package cc.novelia.app.ui.components

import android.graphics.Bitmap
import android.animation.ValueAnimator
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.library.ReadingDestination
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.account.ProfileAccountCard
import cc.novelia.app.ui.book.BookReadingActions
import cc.novelia.app.ui.book.BookFavoriteActions
import cc.novelia.app.ui.shelf.BookFavoriteState
import cc.novelia.app.ui.shelf.CompactCloudControls
import cc.novelia.app.data.network.CloudWebFilter
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class CompactLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cloudToolbarKeepsMenusAndFilterTargetsInsideNarrowLargeFontLayouts() {
        val folders = listOf(Folder("all", "全部收藏"), Folder("reading", "正在阅读的长名称收藏夹"))
        var current by mutableStateOf(folders.first())
        var sort by mutableStateOf("update")
        var expanded by mutableStateOf(false)
        var fontScale by mutableFloatStateOf(1f)
        var created = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppInteractionMode(eInk = false, reducedMotion = true) {
                    NoveliaTheme("dark") {
                        Surface(Modifier.requiredWidth(320.dp).testTag("compact-layout")) {
                            Column {
                                CompactCloudControls(folders, current, { id -> current = folders.first { it.id == id } },
                                    sort, { sort = it }, 0, CloudWebFilter(), { _, _ -> }, "", {}, {}, {},
                                    expanded, { expanded = it }, "本页 20 本", 540.dp, true, true) { close ->
                                    DropdownMenuItem({ Text("新建收藏夹") }, { close(); created = true })
                                }
                            }
                        }
                    }
                }
            }
        }
        for(scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            capture("cloud-toolbar-$scale")
            val toolbar = compose.onNodeWithTag("compact-layout").fetchSemanticsNode().boundsInRoot
            listOf("cloud-folder-picker", "cloud-sort-picker", "cloud-filter-toggle").forEach { tag ->
                val button = compose.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                val bounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue("$tag stays inside toolbar", bounds.left >= toolbar.left && bounds.right <= toolbar.right)
            }
            assertTextFits("更新时间")
        }
        compose.onNodeWithTag("cloud-folder-picker").performClick()
        compose.onNodeWithText(folders[1].title).performClick()
        compose.runOnIdle { assertEquals("reading", current.id) }
        compose.onNodeWithTag("cloud-sort-picker").performClick()
        compose.onNodeWithText("收藏时间").performClick()
        compose.runOnIdle { assertEquals("create", sort) }
        compose.onNodeWithTag("cloud-filter-toggle").performClick()
        compose.onNodeWithTag("cloud-filter-sheet").assertIsDisplayed()
        compose.onNodeWithTag("cloud-filter-close").performClick()
        compose.onNodeWithTag("cloud-folder-picker").performClick()
        compose.onNodeWithText("新建收藏夹").performClick()
        compose.runOnIdle { assertTrue(created); assertTrue(!expanded) }
        compose.onNodeWithTag("cloud-filter-sheet").assertDoesNotExist()
    }

    @Test fun readingChapterWrapsInsideItsButtonAndSecondaryActionsStayIndependent() {
        val chapter = ReadingDestination("chapter-20", 20, "关于春天的邂逅、离别和我们尚未说完的那些话")
        var destination by mutableStateOf<ReadingDestination?>(chapter)
        var favoriteState by mutableStateOf(BookFavoriteState(false, null))
        var fontScale by mutableFloatStateOf(1f)
        var read = 0
        var localFavorite = 0
        var cloudFavorite = 0
        var download = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NoveliaTheme("dark") {
                    Surface(Modifier.requiredWidth(320.dp).testTag("compact-layout")) {
                        BookReadingActions(destination, true, favoriteState,
                            onLocalFavorite = { localFavorite++ }, onCloudFavorite = { cloudFavorite++ },
                            onRead = { read++ }, onDownload = { download++ })
                    }
                }
            }
        }
        for(scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { fontScale = scale; favoriteState = BookFavoriteState(false, null) }
            assertTextFits(chapter.label)
            assertTextFits("收藏到本地")
            assertTextFits("收藏到云端")
            val button = compose.onNodeWithTag("book-read-action").assertHeightIsAtLeast(76.dp).fetchSemanticsNode().boundsInRoot
            val title = compose.onNodeWithTag("book-resume-chapter", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(title.left > button.left && title.right < button.right && title.bottom < button.bottom)
            if(scale == 1f || scale == 2f) capture("book-reading-$scale")
            compose.runOnIdle { favoriteState = BookFavoriteState(false, "reading", "PUT") }
            assertTextFits("云端收藏待同步")
            compose.runOnIdle { favoriteState = BookFavoriteState(true, "reading", "PUT", "本地追更", "云端待读") }
            for((tag, name) in listOf("book-local-favorite" to "本地追更", "book-cloud-favorite" to "云端待读")) {
                val action = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                val folder = compose.onNodeWithTag("$tag-folder", useUnmergedTree = true)
                    .assertIsDisplayed().assertTextEquals(name).fetchSemanticsNode().boundsInRoot
                assertTrue("收藏夹应显示在对应按钮内部", folder.top >= action.top && folder.bottom <= action.bottom && folder.left >= action.left && folder.right <= action.right)
            }
            assertTextFits("云端收藏待同步")
            if(scale == 1f) {
                val actionHeights = listOf("book-local-favorite", "book-cloud-favorite", "book-download")
                    .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.height }
                assertEquals("同排操作按钮应等高", 1, actionHeights.distinct().size)
            }
            compose.runOnIdle { favoriteState = favoriteState.copy(localFolderName = "本地收藏夹名称非常长而且只允许单行显示", cloudFolderName = "云端收藏夹名称非常长而且只允许单行显示") }
            for(tag in listOf("book-local-favorite-folder", "book-cloud-favorite-folder")) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("收藏夹长名称只能占一行", 1, layouts.single().lineCount)
                assertTrue("收藏夹长名称应省略而非增加高度", layouts.single().isLineEllipsized(0))
            }
        }
        compose.runOnIdle { favoriteState = BookFavoriteState(true, null) }
        compose.onNodeWithTag("book-local-favorite-folder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-cloud-favorite-folder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("已本地收藏").assertIsDisplayed()
        compose.onNodeWithText("收藏到云端").assertIsDisplayed()
        compose.runOnIdle { favoriteState = BookFavoriteState(false, "reading") }
        compose.onNodeWithText("收藏到本地").assertIsDisplayed()
        compose.onNodeWithText("已云端收藏").assertIsDisplayed()
        compose.onNodeWithTag("book-resume-chapter", useUnmergedTree = true).performTouchInput { click(center) }
        compose.onNodeWithTag("book-local-favorite").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("book-cloud-favorite").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("book-download").assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, read); assertEquals(1, localFavorite); assertEquals(1, cloudFavorite); assertEquals(1, download); destination = null }
        compose.onNodeWithTag("book-read-action").assertIsNotEnabled()
        compose.onNodeWithTag("book-resume-chapter", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun favoriteChangesAnimateSizeInBothDirectionsAndReducedMotionFinishesImmediately() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val empty = BookFavoriteState(false, null)
        val saved = BookFavoriteState(true, "reading", localFolderName = "本地追更", cloudFolderName = "云端待读")
        var favorite by mutableStateOf(empty)
        var reduced by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            AppInteractionMode(eInk = false, reducedMotion = reduced) {
                NoveliaTheme("dark") {
                    Surface(Modifier.requiredWidth(320.dp)) {
                        BookFavoriteActions(favorite, { clicks++ }, {}, onDownload = {})
                    }
                }
            }
        }
        fun height() = compose.onNodeWithTag("book-favorite-actions").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        val compactHeight = height()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { favorite = saved }
        compose.mainClock.advanceTimeBy(64)
        val growingHeight = height()
        compose.mainClock.advanceTimeBy(500)
        val savedHeight = height()
        assertTrue("收藏后应平滑增高", compactHeight < growingHeight && growingHeight < savedHeight)
        compose.onNodeWithTag("book-local-favorite-folder", useUnmergedTree = true).assertTextEquals("本地追更")
        compose.runOnIdle { favorite = empty }
        compose.mainClock.advanceTimeBy((AppMotion.Quick + 64).toLong())
        val shrinkingHeight = height()
        assertTrue("取消收藏后应平滑收起", compactHeight < shrinkingHeight && shrinkingHeight < savedHeight)
        // 尚未收起时再次收藏，应转向最新状态，且始终只有一个点击入口。
        compose.runOnIdle { favorite = saved.copy(localFolderName = "新的收藏夹") }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("book-local-favorite-folder", useUnmergedTree = true).assertTextEquals("新的收藏夹")
        compose.onAllNodesWithTag("book-local-favorite").assertCountEquals(1)
        compose.onNodeWithTag("book-local-favorite").performClick()
        compose.runOnIdle { assertEquals(1, clicks); favorite = empty }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { reduced = true }
        compose.mainClock.advanceTimeBy(48)
        assertEquals("途中启用减少动效应立即完成切换", compactHeight, height())
        compose.onNodeWithTag("book-local-favorite-folder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("book-cloud-favorite-folder", useUnmergedTree = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun accountCardKeepsLogoutBehindMenuAndGuestLoginReachable() {
        var profile by mutableStateOf<Profile?>(Profile("nobyoq", "member", 1743206400L, Long.MAX_VALUE))
        var fontScale by mutableFloatStateOf(1f)
        var logout = 0
        var login = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppInteractionMode(eInk = false, reducedMotion = true) {
                    NoveliaTheme("light") {
                        Surface(Modifier.requiredWidth(320.dp).testTag("compact-layout")) {
                            ProfileAccountCard(profile, false, onLogin = { login++ }, onLogout = { logout++ },
                                modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
        }
        for(scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { fontScale = scale }
            capture("profile-card-$scale")
            compose.onNodeWithTag("profile-account-menu").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("account-permissions-toggle").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            assertTextFits("普通成员")
        }
        compose.onNodeWithText("退出登录").assertDoesNotExist()
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        compose.onNodeWithText("发表评论").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起账号权限").performClick()
        compose.onNodeWithTag("profile-account-menu").performClick()
        compose.runOnIdle { assertEquals(0, logout) }
        compose.onNodeWithTag("profile-logout").performClick()
        compose.runOnIdle { assertEquals(1, logout); profile = null }
        compose.onNodeWithTag("profile-account-menu").assertDoesNotExist()
        compose.onNodeWithText("登录 / 注册").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, login) }
    }

    private fun assertTextFits(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("$text has a text layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            // 按内容宽度布局的短 Text 仍可能保留更宽的段落测量结果；应结合实际
            // 行边界和可见字符范围判断文字是否被裁切。
            for(line in 0 until layout.lineCount) {
                assertTrue("$text line $line fits horizontally at ${layout.layoutInput.density.fontScale}x",
                    layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
                assertTrue("$text line $line fits vertically", layout.getLineTop(line) >= -1f &&
                    layout.getLineBottom(line) <= layout.size.height + 1f)
                assertTrue("$text is not ellipsized", !layout.isLineEllipsized(line))
            }
            assertEquals("$text includes every character", text.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onNodeWithTag("compact-layout").captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "compact-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
