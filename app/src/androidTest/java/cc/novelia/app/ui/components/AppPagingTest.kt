@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.appHorizontalScroll
import cc.novelia.app.ui.components.screenPageDistance
import cc.novelia.app.ui.reader.ReaderPreferences
import cc.novelia.app.ui.reader.rememberReaderPreferencesState
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.web.PagedSiteWebView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPagingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reducedMotionDisablesEmbeddedPageAnimationsWithoutChangingPagingMode() {
        lateinit var web: PagedSiteWebView
        var ready by mutableStateOf(false)
        compose.setContent {
            val context = LocalContext.current
            val view = remember { PagedSiteWebView(context).apply {
                web = this
                settings.javaScriptEnabled = true
                reducedMotion = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                        applyMotionPreference(); ready = true
                    }
                }
                // loadData builds a data URL: an unescaped CSS '#' would truncate that URL.
                // This API accepts the original HTML, so the ID selector and fixture remain intact.
                loadDataWithBaseURL(null, "<html><style>#sample{animation:pulse 2s infinite;transition:opacity 1s}@keyframes pulse{to{opacity:.5}}</style><body><p id='sample'>Motion sample</p></body></html>", "text/html", "UTF-8", null)
            } }
            DisposableEffect(view) { onDispose { view.destroy() } }
            AndroidView(factory = { view }, modifier = Modifier.size(300.dp))
        }
        compose.waitUntil(15_000) { ready }
        val computed = java.util.concurrent.atomic.AtomicReference<String?>(null)
        fun assertComputedStyle(expected: String) {
            computed.set(null)
            compose.runOnIdle {
                web.evaluateJavascript("(() => { const element = document.getElementById('sample'); if (!element) return 'missing HTML fixture'; const s = getComputedStyle(element); return s.animationName + '|' + s.transitionProperty; })()") { computed.set(it) }
            }
            compose.waitUntil(5_000) { computed.get() != null }
            assertEquals("Embedded page computed motion styles", "\"$expected\"", computed.get())
        }
        assertComputedStyle("none|none")
        compose.runOnIdle {
            assertFalse(web.eInkMode)
            assertEquals(android.view.View.OVER_SCROLL_NEVER, web.overScrollMode)
            web.reducedMotion = false
        }
        assertComputedStyle("pulse|opacity")
    }

    @Test fun lazyListStaysStillUntilReleaseThenJumpsOneScreenWithoutFling() {
        val state = LazyListState()
        var eInk by mutableStateOf(true)
        var pageRequests = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppInteractionMode(eInk, false) { NoveliaTheme("light") {
                    AppLazyColumn(Modifier.width(375.dp).height(460.dp), state, listModifier = Modifier.testTag("pages"), onPageTurn = { pageRequests++ }) {
                        items(40) { Text("第 $it 本小说", Modifier.fillMaxWidth().height(72.dp)) }
                    }
                } }
            }
        }
        fun offset() = state.firstVisibleItemIndex * 72 + state.firstVisibleItemScrollOffset
        compose.onNodeWithText("上一屏").assertIsNotEnabled()
        val list = compose.onNodeWithTag("pages")
        compose.mainClock.autoAdvance = false
        try {
            list.performTouchInput { down(Offset(centerX, height * .8f)); moveTo(Offset(centerX, height * .2f), delayMillis = 240) }
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { assertEquals(0, offset()) }
            list.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(32)
            val after = offset()
            assertEquals(screenPageDistance(state.layoutInfo.viewportSize.height, 48f).toInt(), after)
            assertEquals(1, pageRequests)
            compose.mainClock.advanceTimeBy(1000)
            assertEquals("松手后不应继续惯性滚动", after, offset())
            compose.onNodeWithText("上一屏").performClick()
            compose.mainClock.advanceTimeBy(32)
            assertEquals(0, offset())
            list.performTouchInput { down(center); moveBy(Offset(0f, -120f)); cancel() }
            compose.mainClock.advanceTimeBy(300)
            assertEquals("取消手势不翻页", 0, offset())
            assertEquals(2, pageRequests)
            compose.runOnIdle { eInk = false }
            compose.mainClock.advanceTimeByFrame()
            list.performTouchInput { down(Offset(centerX, height * .8f)); moveTo(Offset(centerX, height * .2f), delayMillis = 300) }
            compose.mainClock.advanceTimeBy(64)
            assertTrue("关闭模式后应恢复随手指滚动", offset() > 0)
            list.performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithText("下一屏").assertDoesNotExist()
    }

    @Test fun formAndHorizontalChoicesPageWithoutStealingVerticalGestures() {
        val vertical = ScrollState(0)
        val horizontal = ScrollState(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppInteractionMode(true, false) { NoveliaTheme("dark") {
                    AppScrollColumn(Modifier.width(375.dp).height(460.dp), state = vertical) {
                        Row(Modifier.testTag("choices").appHorizontalScroll(horizontal)) {
                            repeat(20) { TextButton(onClick = {}) { Text("收藏夹 $it") } }
                        }
                        repeat(30) { Text("设置项 $it", Modifier.fillMaxWidth().height(80.dp)) }
                    }
                } }
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("choices").performTouchInput { down(Offset(width - 12f, centerY)); moveTo(Offset(12f, centerY), delayMillis = 240) }
            compose.mainClock.advanceTimeBy(300)
            assertEquals(0, horizontal.value)
            compose.onNodeWithTag("choices").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(32)
            assertTrue(horizontal.value > 0)
            assertEquals(0, vertical.value)
            compose.onNodeWithTag("choices").performTouchInput { down(center); moveBy(Offset(0f, -140f), delayMillis = 240); up() }
            compose.mainClock.advanceTimeBy(32)
            assertTrue("从横向筛选行开始的纵向手势仍应翻动整页", vertical.value > 0)
            compose.onNodeWithText("下一屏").performClick()
            compose.mainClock.advanceTimeBy(32)
            assertTrue(vertical.value > vertical.viewportSize)
        } finally { compose.mainClock.autoAdvance = true }
        compose.onNodeWithText("设置项 29").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("下一屏").assertIsNotEnabled()
    }

    @Test fun readerSettingsUseButtonsInStaticDialogAndRestoreSliders() {
        var eInk by mutableStateOf(true)
        var reader by mutableStateOf(ReaderSettings(eInkMode = true))
        var open by mutableStateOf(true)
        compose.setContent {
            AppInteractionMode(eInk, false) { NoveliaTheme("light") {
                val preferenceState = rememberReaderPreferencesState()
                if(open) AppSheet(onDismissRequest = { open = false }) { ReaderPreferences(reader, state = preferenceState) { reader = it } }
            } }
        }
        compose.onNodeWithText("关闭面板").assertIsDisplayed()
        compose.onNode(hasContentDescription("增大 字号", substring = true)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(20f, reader.fontSize, .01f) }
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithText("工具栏透明度", substring = true).performScrollTo()
        compose.onNodeWithTag("reader-toolbar-transparency").assertExists()
        compose.runOnIdle { eInk = false }
        compose.onNodeWithText("关闭面板").assertDoesNotExist()
        compose.onNodeWithTag("reader-toolbar-transparency").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("增大", substring = true)).assertCountEquals(0)
    }

    @Test fun embeddedWebPageUsesReleaseToPageAndRestoresTouchScrolling() {
        var ready by mutableStateOf(false)
        lateinit var web: PagedSiteWebView
        compose.setContent {
            val context = LocalContext.current
            val view = remember { PagedSiteWebView(context).apply {
                web = this
                settings.javaScriptEnabled = true
                eInkMode = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String?) { applyMotionPreference(); ready = true }
                }
                loadData("<html><meta name='viewport' content='width=device-width'><body>" + (1..60).joinToString("") { "<p style='height:70px'>第 $it 段正文</p>" } + "</body></html>", "text/html", "UTF-8")
            } }
            DisposableEffect(view) { onDispose { view.destroy() } }
            AndroidView(factory = { view }, modifier = Modifier.width(375.dp).height(460.dp).testTag("web-pages"))
        }
        compose.waitUntil(15_000) { ready }
        val viewport = compose.onNodeWithTag("web-pages")
        viewport.performTouchInput { down(Offset(centerX, height * .8f)); moveTo(Offset(centerX, height * .2f), delayMillis = 300) }
        compose.runOnIdle { assertEquals(0, web.scrollY) }
        viewport.performTouchInput { up() }
        val pageOffset = compose.runOnIdle {
            assertEquals(screenPageDistance(web.height, 48 * web.resources.displayMetrics.density).toInt(), web.scrollY)
            web.scrollY
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(pageOffset, web.scrollY); web.eInkMode = false }
        viewport.performTouchInput { down(Offset(centerX, height * .8f)); moveTo(Offset(centerX, height * .2f), delayMillis = 300) }
        compose.runOnIdle { assertTrue(web.scrollY > pageOffset) }
        viewport.performTouchInput { up() }
    }
}
