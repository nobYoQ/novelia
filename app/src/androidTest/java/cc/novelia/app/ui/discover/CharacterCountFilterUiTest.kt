package cc.novelia.app.ui.discover

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.catalog.CharacterCountFilter
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.CharacterCountFilterFields
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CharacterCountFilterUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun invalidRangeCannotApplyAndAValidCustomRangeIncludesItsEndpoints() {
        var applied by mutableStateOf(DiscoverFilters())
        var open by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if(open) DiscoverFilterSheet(true, applied, false, { open = false }, { applied = it; open = false }, {}, {})
            }
        }
        compose.onNodeWithTag("characters-minimum").performScrollTo().performTextReplacement("500000")
        compose.onNodeWithTag("characters-maximum").performScrollTo().performTextReplacement("100000")
        compose.onNodeWithTag("discover-filter-apply").assertIsNotEnabled()
        compose.onNodeWithTag("characters-maximum").performTextReplacement("500000")
        compose.runOnIdle { assertFalse(applied.local.characters.active) }
        compose.onNodeWithTag("discover-filter-apply").performClick()
        compose.runOnIdle {
            val filter = applied.local.characters
            assertTrue(filter.matches(500000))
            assertFalse(filter.matches(499999))
            assertFalse(filter.matches(null))
            open = true
        }
        compose.onNodeWithTag("discover-filter-reset").performClick()
        compose.onNodeWithTag("discover-filter-close").performClick()
        compose.runOnIdle { assertTrue(applied.local.characters.active); open = true }
        compose.onNodeWithTag("discover-filter-reset").performClick()
        compose.onNodeWithTag("discover-filter-apply").performClick()
        compose.runOnIdle { assertFalse(applied.local.characters.active) }
    }

    @Test fun narrowLayoutBalancesPresetsAndSeparatesSearchHelpAtLargeFontSizes() {
        var filter by mutableStateOf(CharacterCountFilter())
        var scale by mutableFloatStateOf(1f)
        var assistantOpened = false
        var syntaxOpened = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                NoveliaTheme("dark") {
                    androidx.compose.material3.Surface {
                        AppScrollColumn(Modifier.requiredWidth(320.dp).fillMaxHeight().testTag("character-filter-preview"),
                            contentModifier = Modifier.padding(bottom = 24.dp)) {
                            CharacterCountFilterFields(filter) { filter = it }
                            Spacer(Modifier.height(24.dp))
                            DiscoverFilterHelp({ assistantOpened = true }, { syntaxOpened = true })
                        }
                    }
                }
            }
        }
        val presetBounds = listOf("不限", "10 万以下", "10～50 万", "50 万及以上").map {
            compose.onNodeWithText(it).getUnclippedBoundsInRoot()
        }
        assertEquals(presetBounds[0].top, presetBounds[1].top)
        assertEquals(presetBounds[2].top, presetBounds[3].top)
        assertTrue(presetBounds[2].top > presetBounds[0].bottom)
        val minimum = compose.onNodeWithTag("characters-minimum").getUnclippedBoundsInRoot()
        val maximum = compose.onNodeWithTag("characters-maximum").getUnclippedBoundsInRoot()
        assertEquals(minimum.top, maximum.top)
        assertTrue(maximum.left > minimum.right)
        capture("discover-character-filter.png")
        compose.onNodeWithText("包含字数未知作品").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(filter.includeUnknown) }
        compose.onNodeWithTag("discover-filter-help").performScrollTo()
        capture("discover-filter-help.png")
        compose.onNodeWithText("打开辅助搜索").performScrollTo().performClick()
        compose.onNodeWithText("查看搜索语法").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(assistantOpened); assertTrue(syntaxOpened); scale = 2f }
        compose.onNodeWithTag("characters-minimum").performScrollTo()
        val largeMinimum = compose.onNodeWithTag("characters-minimum").getUnclippedBoundsInRoot()
        val largeMaximum = compose.onNodeWithTag("characters-maximum").getUnclippedBoundsInRoot()
        assertTrue("大字号下上下限应自动分行", largeMaximum.top > largeMinimum.bottom)
        capture("discover-character-filter-large-font.png")
        compose.onNodeWithText("查看搜索语法").performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("character-filter-preview").captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
