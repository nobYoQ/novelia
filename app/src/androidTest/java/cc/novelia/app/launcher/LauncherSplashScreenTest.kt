package cc.novelia.app.launcher

import android.util.TypedValue
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

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
}
