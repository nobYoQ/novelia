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
                LauncherIcon("night", "夜读蓝", R.drawable.launcher_night, "night")
            ), enabledIds = setOf("default")
        ))
        compose.setContent { MaterialTheme { LauncherIconPicker(state, { state = state.copy(selectedId = it) }) } }
        compose.onNodeWithTag("launcher-icon-default").assertIsSelected()
        compose.onNodeWithTag("launcher-icon-night").performClick().assertIsSelected()
        compose.onNodeWithTag("launcher-icon-pending").assertTextContains("等待退到后台", substring = true)
        compose.onNodeWithText("当前使用").assertExists()
        compose.onNodeWithText("取消待切换").performClick()
        compose.onNodeWithTag("launcher-icon-default").assertIsSelected()
        compose.onNodeWithTag("launcher-icon-pending").assertDoesNotExist()
    }
}
