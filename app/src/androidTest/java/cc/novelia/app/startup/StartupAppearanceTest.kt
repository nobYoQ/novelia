package cc.novelia.app.startup

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StartupAppearanceTest {
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("startup_appearance_test", Context.MODE_PRIVATE)

    @Before fun prepare() { assertTrue(preferences.edit().clear().commit()) }
    @After fun cleanup() { assertTrue(preferences.edit().clear().commit()) }

    @Test fun coldStartCanReadAppearanceWithoutOpeningTheLibrary() {
        val first = StartupAppearanceStore(preferences)
        first.update(StartupAppearance("dark", true))
        // commit 等待前面的异步缓存写入，模拟重新创建启动状态。
        assertTrue(preferences.edit().commit())
        val restarted = StartupAppearanceStore(preferences)
        assertEquals(StartupAppearance("dark", true), restarted.state.value)
        assertEquals("Amoled", restarted.state.value.splashVariant)

        restarted.update(StartupAppearance("system", true))
        val followingSystem = StartupAppearanceStore(preferences)
        assertEquals("SystemAmoled", followingSystem.state.value.splashVariant)
        followingSystem.update(StartupAppearance("light", true))
        assertEquals("Light", followingSystem.state.value.splashVariant)
        followingSystem.update(StartupAppearance("monochrome", true))
        val monochrome = StartupAppearanceStore(preferences).state.value
        assertEquals("Monochrome", monochrome.splashVariant)
        assertEquals(cc.novelia.app.R.style.Theme_Novelia_Monochrome, monochrome.windowTheme)
    }

    @Test fun anUnknownCachedThemeFallsBackToFollowingTheSystem() {
        assertTrue(preferences.edit().putString("theme", "retired-theme").putBoolean("amoledBlack", true).commit())
        val cache = StartupAppearanceStore(preferences)
        assertEquals(StartupAppearance("system", true), cache.state.value)
        cache.update(StartupAppearance("dark", false))
        assertEquals(StartupAppearance("dark", false), StartupAppearanceStore(preferences).state.value)
    }
}
