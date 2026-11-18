package cc.novelia.app.startup

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.ui.theme.customColorScheme
import cc.novelia.app.ui.saveTestScreenshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StartupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentLoadingStepAndRetryAreVisible() {
        var progress by mutableStateOf(StartupProgress(StartupStep.CONNECTION))
        var retries = 0
        compose.setContent { StartupScreen(StartupAppearance("light"), progress) { retries++ } }
        compose.onNodeWithTag("startup-current-step").assertTextEquals(StartupStep.CONNECTION.description)
        compose.onNodeWithText("正在加载").assertIsDisplayed()
        saveTestScreenshot("problem-startup-loading.png")
        compose.runOnIdle { progress = progress.copy(failed = true) }
        compose.onNodeWithText("待重试").assertIsDisplayed()
        compose.onNodeWithText("重试加载").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun loadingSurfaceAndProgressUseTheSavedPalette() {
        val preset = ThemePreset("rose", "蔷薇", 0x872648, 0xFAEFF3, 0x201219)
        var appearance by mutableStateOf(StartupAppearance("light", palette = preset))
        compose.setContent { StartupScreen(appearance, StartupProgress(StartupStep.CONNECTION)) {} }
        for(mode in listOf("light", "dark")) {
            compose.runOnIdle { appearance = appearance.copy(theme = mode, amoledBlack = mode == "dark") }
            val colors = customColorScheme(preset, mode == "dark", mode == "dark")
            val surface = compose.onRoot().captureToImage().toPixelMap()
            assertEquals(colors.surface.toArgb(), surface[2, surface.height / 2].toArgb())
            val bar = compose.onNodeWithTag("startup-progress").captureToImage().toPixelMap()
            assertEquals(colors.primary.toArgb(), bar[bar.width / 4, bar.height / 2].toArgb())
        }
    }
}
