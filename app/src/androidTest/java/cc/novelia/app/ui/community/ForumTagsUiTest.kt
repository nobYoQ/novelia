package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ForumTag
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ForumTagsUiTest {
    @get:Rule val compose = createComposeRule()
    private val tags = listOf(ForumTag(1, "书单"), ForumTag(2, "工具", 1), ForumTag(3, "教程", 2), ForumTag(4, "资源"))

    @Test fun filterSelectsOneTagAndCanClearByTogglingOrSelectingAll() {
        var selected by mutableStateOf<Long?>(null)
        compose.setContent { NoveliaTheme("light") { Box(Modifier.width(240.dp)) {
            ForumTagFilter(tags, selected) { selected = it }
        } } }
        compose.onNodeWithText("全部标签").assertIsSelected()
        compose.onNodeWithText("书单").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1L, selected) }
        compose.onNodeWithText("教程").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("书单").assertIsNotSelected()
        compose.runOnIdle { assertEquals(3L, selected) }
        compose.onNodeWithText("教程").performScrollTo().performClick()
        compose.onNodeWithText("全部标签").assertIsSelected()
        compose.onNodeWithText("工具").performScrollTo().performClick()
        compose.onNodeWithText("全部标签").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertNull(selected) }
    }

    @Test fun categoryWithoutTagsShowsExplanationAndNoFilter() {
        compose.setContent { NoveliaTheme("light") {
            ForumTagSelector(emptyList(), emptyList(), true) { fail("无标签时不应修改选择") }
            ForumTagFilter(emptyList(), null) { fail("无标签时不应筛选") }
        } }
        compose.onNodeWithText("这个分类暂时没有可用标签。").assertIsDisplayed()
        compose.onNodeWithTag("forum-tag-filter").assertDoesNotExist()
    }

    @Test fun longBadgesWrapWithinNarrowLayoutsInDarkAndEInkModes() {
        var eInk by mutableStateOf(false)
        val longTags = tags + ForumTag(5, "长度较大的标签名称也应完整显示", Int.MIN_VALUE)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalEInkMode provides eInk) {
                NoveliaTheme(if(eInk) "light" else "dark") {
                    Box(Modifier.width(240.dp).testTag("tag-bounds")) { ForumTagBadges(longTags) }
                }
            }
        }
        for(mode in listOf(false, true)) {
            compose.runOnIdle { eInk = mode }
            val bounds = compose.onNodeWithTag("tag-bounds").getUnclippedBoundsInRoot()
            for(tag in longTags) {
                val badge = compose.onNodeWithText(tag.name).assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue(badge.left >= bounds.left && badge.right <= bounds.right)
            }
            assertTrue(compose.onNodeWithText(longTags.last().name).getUnclippedBoundsInRoot().top >
                compose.onNodeWithText(tags.first().name).getUnclippedBoundsInRoot().top)
        }
    }
}
