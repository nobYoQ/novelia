package cc.novelia.app.launcher

import android.util.TypedValue
import android.content.res.Configuration
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import cc.novelia.app.startup.StartupAppearance

class LauncherSplashScreenTest {
    @SdkSuppress(minSdkVersion = 31)
    @Test fun everyInstalledLauncherEntryHasASplashThemeWithTheSameIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icons = AndroidLauncherIconBackend(context).load().icons
        assertTrue("The catalog should include alternative icons", icons.size > 1)
        assertEquals("Every icon needs its own persistent theme", icons.size, icons.map { it.splashTheme }.toSet().size)
        icons.forEach { icon ->
            assertNotEquals("Missing theme for ${icon.id}", 0, icon.splashTheme)
            assertEquals("Theme names must survive upgrades", "Theme.Novelia.Launcher.${icon.id}",
                context.resources.getResourceEntryName(icon.splashTheme))
            val theme = context.resources.newTheme().apply { applyStyle(icon.splashTheme, true) }
            val drawable = TypedValue()
            assertTrue(theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, drawable, true))
            assertEquals("Splash and launcher must use the same artwork: ${icon.id}", icon.drawable, drawable.resourceId)
        }
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun launcherSplashPreservesTheIconAndHonorsAppearanceInBothSystemModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icons = AndroidLauncherIconBackend(context).load().icons
        val light = 0xFFF1F4EC.toInt()
        val dark = 0xFF101512.toInt()
        val black = 0xFF000000.toInt()
        for(systemDark in listOf(false, true)) {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if(systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val resources = context.createConfigurationContext(configuration).resources
            val cases = listOf(
                StartupAppearance("light", true) to light,
                StartupAppearance("monochrome", true) to android.graphics.Color.WHITE,
                StartupAppearance("dark", false) to dark,
                StartupAppearance("dark", true) to black,
                StartupAppearance("system", false) to if(systemDark) dark else light,
                StartupAppearance("system", true) to if(systemDark) black else light
            )
            icons.forEach { icon ->
                assertTrue("All variants must survive resource shrinking", icon.splashThemes.values.all { it != 0 })
                cases.forEach { (appearance, background) ->
                    val theme = resources.newTheme().apply { applyStyle(icon.splashThemeFor(appearance), true) }
                    val value = TypedValue()
                    assertTrue(theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, value, true))
                    assertEquals(icon.drawable, value.resourceId)
                    for(attribute in listOf(android.R.attr.windowSplashScreenBackground, android.R.attr.windowBackground)) {
                        assertTrue(theme.resolveAttribute(attribute, value, true))
                        assertEquals("${icon.id}, $appearance, systemDark=$systemDark", background, value.data)
                    }
                }
            }
        }
    }
}
