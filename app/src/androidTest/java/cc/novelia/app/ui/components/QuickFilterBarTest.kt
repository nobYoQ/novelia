package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import cc.novelia.app.ui.components.base.QuickFilter
import cc.novelia.app.ui.components.base.QuickFilterBar

@RunWith(AndroidJUnit4::class)
class QuickFilterBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun visibleFiltersAdaptToWidthAndSelectionUpdates() {
        var width by mutableStateOf(360.dp)
        var selected by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                QuickFilterBar(
                    listOf(
                        QuickFilter("状态", listOf("全部", "已完成"), selected) { selected = it },
                        QuickFilter("进度", listOf("全部", "连载中"), 0) {},
                        QuickFilter("语言", listOf("全部", "日语"), 0) {},
                    ),
                    Modifier.width(width),
                )
            }
        }

        compose.onNodeWithContentDescription("状态：全部").assertIsDisplayed().assertTextEquals("全部").performClick()
        compose.onNodeWithText("已完成").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("状态：已完成").assertIsDisplayed().assertTextEquals("已完成")
        compose.onNodeWithContentDescription("进度：全部").assertIsDisplayed().assertTextEquals("全部")
        compose.onNodeWithContentDescription("语言：全部").assertIsDisplayed().assertTextEquals("全部")
        compose.runOnIdle { assertEquals(1, selected); width = 180.dp }
        compose.onNodeWithContentDescription("状态：已完成").assertIsDisplayed().assertTextEquals("已完成")
        compose.onNodeWithContentDescription("进度：全部").assertDoesNotExist()
        compose.onNodeWithContentDescription("语言：全部").assertDoesNotExist()
    }
}
