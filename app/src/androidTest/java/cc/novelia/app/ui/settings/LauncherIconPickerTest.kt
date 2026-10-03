package cc.novelia.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.R
import cc.novelia.app.launcher.LauncherIcon
import cc.novelia.app.launcher.LauncherIconState
import org.junit.Rule
import org.junit.Test

class LauncherIconPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pickingIconShowsPendingStateAndCancelRestoresCurrentChoice() {
        var state by mutableStateOf(LauncherIconState(
            icons = listOf(
                LauncherIcon("default", "经典绿", R.drawable.ic_launcher, "default"),
                LauncherIcon("xingchuan_green", "星川绿", R.drawable.launcher_xingchuan_green, "xingchuan_green"),
                LauncherIcon("xingchuan_green_01", "星川绿·心动", R.drawable.launcher_xingchuan_green_01, "xingchuan_green_01"),
                LauncherIcon("xingchuan_green_02", "星川绿·招手", R.drawable.launcher_xingchuan_green_02, "xingchuan_green_02")
            ), enabledIds = setOf("default")
        ))
        compose.setContent { MaterialTheme { LauncherIconPicker(state, { state = state.copy(selectedId = it) }) } }
        listOf("xingchuan_green", "xingchuan_green_01", "xingchuan_green_02").forEach { id ->
            compose.onNodeWithTag("launcher-icon-$id").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("launcher-icon-default").performScrollTo()
        compose.onNodeWithTag("launcher-icon-default").assertIsSelected()
        compose.onNodeWithTag("launcher-icon-xingchuan_green_01").performClick().assertIsSelected()
        compose.onNodeWithTag("launcher-icon-pending").assertTextEquals("已选择星川绿·心动")
        compose.onNodeWithText("图标会在应用退到后台时切换。", substring = true).assertExists()
        compose.onNodeWithText("当前使用").assertExists()
        compose.onNodeWithText("取消待切换").performClick()
        compose.onNodeWithTag("launcher-icon-default").assertIsSelected()
        compose.onNodeWithTag("launcher-icon-pending").assertDoesNotExist()
    }
}
