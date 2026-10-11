package cc.novelia.app.ui.components

import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reducedMotionAlertUsesStaticWindowAndKeepsItsActions() {
        var open by mutableStateOf(true)
        var window: Window? = null
        var confirmed = false
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    if(open) AppAlertDialog(onDismissRequest = { open = false },
                        title = {
                            val view = LocalView.current
                            SideEffect { window = (view.parent as? DialogWindowProvider)?.window }
                            Text("静态确认框")
                        }, confirmButton = {
                            TextButton(onClick = { confirmed = true; open = false }) { Text("完成操作") }
                        })
                }
            }
        }
        compose.onNodeWithText("静态确认框").assertIsDisplayed()
        compose.runOnIdle {
            assertNotNull(window)
            assertEquals(0, window!!.attributes.windowAnimations)
        }
        compose.onNodeWithText("完成操作").performClick()
        compose.onNodeWithText("静态确认框").assertDoesNotExist()
        compose.runOnIdle { assertEquals(true, confirmed) }
    }

    @Test fun eInkDropdownOpensAndClosesWithoutRetainingOutgoingMenu() {
        var open by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalEInkMode provides true, LocalReducedMotion provides false) {
                    Box {
                        TextButton(onClick = { open = true }) { Text("显示菜单") }
                        AppDropdownMenu(open, { open = false }) {
                            DropdownMenuItem(text = { Text("完成菜单操作") }, onClick = { open = false })
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("显示菜单").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("完成菜单操作").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("完成菜单操作").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
}
