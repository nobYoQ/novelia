@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReducedMotionSheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun ordinaryScreenUsesStaticPanelWhenMotionIsReduced() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalEInkMode provides false, LocalReducedMotion provides true) {
                    AppSheet(onDismissRequest = {}) { Text("静态面板内容") }
                }
            }
        }
        compose.onNodeWithText("关闭面板").assertIsDisplayed()
        compose.onNodeWithText("静态面板内容").assertIsDisplayed()
    }
}
