package cc.novelia.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cc.novelia.app.ui.theme.AppMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RootDestinationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabletShelfKeepsItsWidthWhenEnteringAndLeavingReader() = verifyTransition(1024.dp)

    // 去掉 80 dp 侧栏会使 800 dp 的书架突然越过 840 dp 双栏断点。
    @Test fun transitionDoesNotTemporarilySwitchTheShelfToTwoPanes() = verifyTransition(880.dp)

    @Test fun phoneShelfKeepsItsHeightWhenEnteringAndLeavingReader() = verifyTransition(390.dp)

    private fun verifyTransition(width: Dp) {
        lateinit var nav: NavHostController
        val rootSizes = mutableListOf<IntSize>()
        val readerSizes = mutableListOf<IntSize>()
        val expandedLayouts = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width, 640.dp)) {
                    Scaffold { padding ->
                        val bottomPadding = PaddingValues(bottom = padding.calculateBottomPadding())
                        Box(Modifier.fillMaxSize().padding(bottomPadding).consumeWindowInsets(bottomPadding)) {
                            nav = rememberNavController()
                            NavHost(nav, startDestination = "shelf", modifier = Modifier.fillMaxSize(),
                                enterTransition = { fadeIn(tween(AppMotion.Standard)) },
                                exitTransition = { fadeOut(tween(AppMotion.Exit)) },
                                popEnterTransition = { fadeIn(tween(AppMotion.Standard)) },
                                popExitTransition = { fadeOut(tween(AppMotion.Exit)) }) {
                                composable("shelf") {
                                    RootDestinationLayout("shelf", nav::switchRootTab) {
                                        BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged { rootSizes += it }) {
                                            val expanded = maxWidth >= 840.dp
                                            SideEffect { expandedLayouts += expanded }
                                        }
                                    }
                                }
                                composable("reader") {
                                    Box(Modifier.fillMaxSize().onSizeChanged { readerSizes += it })
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        var originalSize = IntSize.Zero
        var originalExpanded = false
        compose.runOnIdle {
            originalSize = rootSizes.last()
            originalExpanded = expandedLayouts.last()
            assertEquals(width >= 920.dp, originalExpanded)
            // 系统边距在首屏建立时可能补发；只检查首屏稳定后的导航过程。
            rootSizes.clear()
            rootSizes += originalSize
            expandedLayouts.clear()
            expandedLayouts += originalExpanded
        }
        compose.mainClock.autoAdvance = false
        repeat(2) {
            compose.runOnIdle { nav.navigate("reader") }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                assertTrue("Outgoing shelf must still exist during the transition", nav.visibleEntries.value.size >= 2)
                assertTrue("Shelf must not resize: expected $originalSize, observed $rootSizes", rootSizes.all { it == originalSize })
                assertTrue("Shelf must not cross the two-pane breakpoint", expandedLayouts.all { it == originalExpanded })
                val readerSize = readerSizes.last()
                if(width >= 600.dp) {
                    assertTrue("Reader uses the width previously occupied by the rail", readerSize.width > originalSize.width)
                    assertEquals(originalSize.height, readerSize.height)
                } else {
                    assertEquals(originalSize.width, readerSize.width)
                    assertTrue("Reader uses the height previously occupied by the bottom bar", readerSize.height > originalSize.height)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.runOnIdle { assertTrue(nav.popBackStack()) }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                assertTrue("Returning shelf keeps $originalSize, observed $rootSizes", rootSizes.all { it == originalSize })
                assertTrue("Outgoing reader must not shrink: $readerSizes", readerSizes.distinct().size == 1)
                assertTrue("Returning shelf keeps its original pane layout", expandedLayouts.all { it == originalExpanded })
            }
            compose.mainClock.advanceTimeBy(300)
        }
        compose.mainClock.autoAdvance = true
    }
}
