package cc.novelia.app.ui.markdown

import cc.novelia.app.NoveliaApplication
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.ui.markdown.HeadingAnchorSpan
import cc.novelia.app.ui.markdown.MarkdownAnchors
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.markdown.RatingSpan
import cc.novelia.app.ui.markdown.SpoilerSpan
import cc.novelia.app.ui.markdown.SpoilerTextView
import cc.novelia.app.ui.markdown.rememberMarkdownRenderer
import cc.novelia.app.ui.markdown.textOffsetAt
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import io.noties.markwon.Markwon
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.SchemeHandler
import io.noties.markwon.image.file.FileSchemeHandler
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteMarkdownInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: View
    private lateinit var nav: NavHostController

    private fun show(source: String, theme: String = "light", localImageFixture: Boolean = false, imageHandler: SchemeHandler? = null, documentUrl: String? = null, articleList: Boolean = false) {
        val app = instrumentation.targetContext.applicationContext as NoveliaApplication
        compose.setContent {
            NoveliaTheme(theme) {
                root = LocalView.current
                nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val c = remember { AppController(app, nav, scope, SnackbarHostState()) }
                val standard = rememberMarkdownRenderer(c)
                val renderer = remember(standard, localImageFixture) {
                    if (!localImageFixture && imageHandler == null) standard else Markwon.builderNoCore(app).usePlugins(standard.plugins.map {
                        // The production renderer plus a file loader for offline test fixtures.
                        if (it is ImagesPlugin) ImagesPlugin.create { plugin -> plugin.addSchemeHandler(imageHandler ?: FileSchemeHandler.create()) } else it
                    }).build()
                }
                NavHost(nav, startDestination = "sample") {
                    composable("sample") {
                        Surface(Modifier.fillMaxSize()) {
                            if (articleList) {
                                val scroll = rememberLazyListState()
                                LazyColumn(state = scroll, contentPadding = PaddingValues(24.dp), modifier = Modifier.fillMaxSize().testTag("anchor-list")) {
                                    item { Text("帖子标题") }
                                    item { MarkdownText(c, source, Modifier.testTag("site-markdown"), renderer, documentUrl,
                                        onAnchorScroll = { scroll.scrollToItem(1, it) }) }
                                }
                            } else Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                                MarkdownText(c, source, Modifier.testTag("site-markdown"), renderer, documentUrl)
                            }
                        }
                    }
                    composable("article/{id}") { Text("站内帖子：${it.arguments?.getString("id")}") }
                    composable("book/{provider}/{id}") { Text("站内书籍") }
                    composable("reader/{provider}/{id}/{chapter}") { Text("站内章节") }
                    composable("web?url={url}") { Text("站内网页：${it.arguments?.getString("url")}") }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun localDirectoryLinksScrollWithinTheSameArticleIncludingClosedSections() {
        val page = "https://n.novelia.cc/forum/64f3d63f794cbb1321145c07"
        val filler = "普通正文\n\n".repeat(30)
        show("# 目录\n\n[去生成](#%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB)\n\n[去安装]($page#如何安装扩展)\n\n[去重复标题](#说明-1)\n\n[去折叠](#隐藏标题)\n\n" +
            filler + "## 如何生成机翻\n\n[返回目录](#目录)\n\n" + filler + "## 如何安装扩展\n\n[返回目录](#目录)\n\n" +
            filler + "## 说明\n\n" + filler + "## 说明\n\n[返回目录](#目录)\n\n" +
            "::: details 折叠节\n## 隐藏标题\n\n[返回目录](#目录)\n\n" + filler + ":::\n\n" + filler,
            documentUrl = page, articleList = true)
        val initialEntry = nav.currentBackStackEntry!!.id
        fun tapVisible(label: String) {
            var target = Offset.Zero
            val bounds = compose.onNodeWithTag("anchor-list").fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                val view = textView()
                val position = IntArray(2).also(view::getLocationOnScreen)
                // There are several return links; use the one currently visible.
                val indices = Regex(Regex.escape(label)).findAll(view.text).map { it.range.first }
                val index = indices.first { start ->
                    val line = view.layout.getLineForOffset(start)
                    val y = position[1] + view.totalPaddingTop + view.layout.getLineTop(line)
                    y >= bounds.top && y < bounds.bottom
                }
                val line = view.layout.getLineForOffset(index)
                target = Offset(position[0] + view.totalPaddingLeft + view.layout.getPrimaryHorizontal(index) + 5 - bounds.left,
                    position[1] + view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f - bounds.top)
            }
            compose.onNodeWithTag("anchor-list").performTouchInput { click(target) }
            compose.waitForIdle()
        }
        fun assertAtHeading(slug: String) {
            val bounds = compose.onNodeWithTag("anchor-list").fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                assertEquals(initialEntry, nav.currentBackStackEntry!!.id)
                val view = textView()
                val heading = MarkdownAnchors(view.tag as org.commonmark.node.Node).find(slug)!!
                val text = view.text as Spanned
                val marker = text.getSpans(0, text.length, HeadingAnchorSpan::class.java).single { it.heading === heading }
                val position = IntArray(2).also(view::getLocationOnScreen)
                val top = position[1] + view.totalPaddingTop + view.layout.getLineTop(view.layout.getLineForOffset(text.getSpanStart(marker)))
                assertTrue("标题应在列表顶部：$slug top=$top, viewport=$bounds", top >= bounds.top && top < bounds.top + 120)
                assertEquals(0, view.scrollY)
            }
        }
        listOf("去生成" to "如何生成机翻", "去安装" to "如何安装扩展", "去重复标题" to "说明-1", "去折叠" to "隐藏标题").forEach { (label, slug) ->
            tapVisible(label)
            assertAtHeading(slug)
            tapVisible("返回目录")
            assertAtHeading("目录")
        }
    }

    @Test fun pageAnchorUsesTheCurrentArticleAddress() {
        val page = "https://n.novelia.cc/forum/64f3d63f794cbb1321145c07"
        show("[如何生成机翻](#%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB)", documentUrl = page)
        tap("如何生成机翻")
        compose.runOnIdle {
            assertEquals("$page#%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB", nav.currentBackStackEntry?.arguments?.getString("url"))
        }
    }

    @Test fun foldingImagesKeepsLoadedDrawablesAndTheTappedHeadingInPlace() {
        val context = instrumentation.targetContext
        val file = File.createTempFile("fold-image-", ".png", context.cacheDir)
        val release = java.util.concurrent.CountDownLatch(1)
        val loads = java.util.concurrent.atomic.AtomicInteger()
        try {
            val bitmap = Bitmap.createBitmap(360, 180, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val handler = object : SchemeHandler() {
                override fun supportedSchemes() = listOf("file")
                override fun handle(raw: String, uri: android.net.Uri): io.noties.markwon.image.ImageItem {
                    loads.incrementAndGet()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    return FileSchemeHandler.create().handle(raw, uri)
                }
            }
            show("前文\n\n::: details 第一组\n![测试图片](${file.toURI()})\n:::\n\n::: details 第二组\n第二组正文\n:::\n\n" + "后文\n\n".repeat(30), imageHandler = handler)
            tap("第一组")
            var headingBefore = 0
            compose.runOnIdle {
                val view = textView()
                val position = IntArray(2).also(view::getLocationOnScreen)
                headingBefore = position[1] + view.layout.getLineTop(view.layout.getLineForOffset(view.text.indexOf("第一组")))
            }
            release.countDown()
            compose.waitUntil(10_000) { (textView().text as Spanned).getSpans(0, textView().text.length, AsyncDrawableSpan::class.java).singleOrNull()?.drawable?.hasResult() == true }
            compose.waitForIdle()
            lateinit var loaded: AsyncDrawableSpan
            compose.runOnIdle {
                val view = textView()
                val position = IntArray(2).also(view::getLocationOnScreen)
                assertEquals(headingBefore, position[1] + view.layout.getLineTop(view.layout.getLineForOffset(view.text.indexOf("第一组"))))
                assertEquals(0, view.scrollY)
                loaded = (view.text as Spanned).getSpans(0, view.text.length, AsyncDrawableSpan::class.java).single()
            }
            // Toggling another block must not reload the image above that block.
            tap("第二组")
            compose.runOnIdle { assertSame(loaded, (textView().text as Spanned).getSpans(0, textView().text.length, AsyncDrawableSpan::class.java).single()) }
            tap("第一组")
            compose.runOnIdle { assertFalse(textView().text.contains("测试图片")) }
            tap("第一组")
            compose.runOnIdle {
                val restored = (textView().text as Spanned).getSpans(0, textView().text.length, AsyncDrawableSpan::class.java).single()
                assertSame(loaded, restored)
                assertTrue(restored.drawable.hasResult())
                assertEquals(1, loads.get())
                assertEquals(0, textView().scrollY)
            }
        } finally { release.countDown(); file.delete() }
    }

    private fun textView(): SpoilerTextView {
        fun find(view: View): SpoilerTextView? {
            if (view is SpoilerTextView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(root.rootView)!!
    }

    private fun point(label: String): Offset {
        var point = Offset.Zero
        compose.runOnIdle {
            val view = textView()
            val start = view.text.indexOf(label)
            assertTrue("应找到 $label，实际为 ${view.text}", start >= 0)
            val line = view.layout.getLineForOffset(start)
            point = Offset(view.totalPaddingLeft + view.layout.getPrimaryHorizontal(start) + 5,
                view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f)
        }
        return point
    }

    private fun tap(label: String) {
        val offset = point(label)
        compose.onNodeWithTag("site-markdown").performTouchInput {
            advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong() + 50)
            click(offset)
        }
    }

    @Test fun detailsAndHalfStarsRenderAndSpoilersSurviveToggling() {
        show("正文 !!外部剧透!!\n\n::: star 4.5\n\n!!\n::: details 点击展开\n折叠内容 **粗体**\n\n!!内部剧透!!\n\n::: details 内层\n深层内容\n:::\n:::\n!!", "dark")
        compose.runOnIdle {
            val text = textView().text as Spanned
            assertEquals(4.5, text.getSpans(0, text.length, RatingSpan::class.java).single().value, 0.0)
            assertFalse(text.contains("折叠内容"))
            assertFalse(text.contains("!!"))
        }
        tap("外部剧透")
        tap("点击展开")
        compose.runOnIdle {
            val text = textView().text as Spanned
            assertTrue(text.contains("折叠内容"))
            assertFalse(text.contains("深层内容"))
            val spoilers = text.getSpans(0, text.length, SpoilerSpan::class.java).sortedBy { text.getSpanStart(it) }
            assertTrue(spoilers[0].revealed)
            assertFalse(spoilers[1].revealed)
        }
        tap("内层")
        compose.runOnIdle { assertTrue(textView().text.contains("深层内容")) }
        screenshot("site-markdown-expanded")
        tap("点击展开")
        compose.runOnIdle { assertFalse(textView().text.contains("折叠内容")) }
        screenshot("site-markdown-collapsed")
        tap("点击展开")
        compose.runOnIdle { assertTrue(textView().text.contains("深层内容")) }
    }

    @Test fun explicitBareAndHiddenLinksNavigateOnceToTheRightDestination() {
        val captured = mutableListOf<String>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) {
                    captured += intent.dataString.orEmpty()
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            show("[外部链接](https://example.com/a)\n\n裸网址 https://example.org/b，中文分隔。\n\n!![剧透链接](/forum/abc123)!!\n\n[书籍](/wenku/abc123)\n\n[章节](/novel/syosetu/n1234/5)\n\n[站内工具](/workspace/sakura)")
            tap("外部链接")
            compose.runOnIdle { assertEquals(listOf("https://example.com/a"), captured) }
            tap("https://example.org/b")
            compose.runOnIdle { assertEquals(listOf("https://example.com/a", "https://example.org/b"), captured) }
            tap("剧透链接")
            compose.runOnIdle { assertEquals("sample", nav.currentDestination?.route) }
            tap("剧透链接")
            compose.onNodeWithText("站内帖子：abc123").assertIsDisplayed()
            compose.runOnIdle { nav.popBackStack() }
            tap("书籍")
            compose.onNodeWithText("站内书籍").assertIsDisplayed()
            compose.runOnIdle { nav.popBackStack() }
            tap("章节")
            compose.onNodeWithText("站内章节").assertIsDisplayed()
            compose.runOnIdle { nav.popBackStack() }
            tap("站内工具")
            compose.onNodeWithText("站内网页：https://n.novelia.cc/workspace/sakura").assertIsDisplayed()
            compose.runOnIdle { assertEquals(2, captured.size) }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun legacyBareLinkWithMixedParenthesesOpensTheNativeBook() {
        show("https://books.fishhawk.top/novel/hameln/270019(大受好评）",
            documentUrl = "https://n.novelia.cc/forum/675590fcca0084226562ac36")
        tap("https://books.fishhawk.top")
        compose.onNodeWithText("站内书籍").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("hameln", nav.currentBackStackEntry?.arguments?.getString("provider"))
            assertEquals("270019", nav.currentBackStackEntry?.arguments?.getString("id"))
        }
    }

    @Test fun mixedHostTableLinksRespondToRealTaps() {
        show("""
            | 书名 | 推荐指数 | 书评 |
            | :- | :- | :- |
            | [旧小说](https://books.fishhawk.top/novel/syosetu/n2544jm) | 5 | 说明 |
            | [旧短篇](https://books.fishhawk.top/novel/pixiv/s20401120) | 4 | 说明 |
            | [新小说](https://n.novelia.cc/novel/kakuyomu/16818792440198448037) | 3 | 说明 |
        """.trimIndent(), documentUrl = "https://n.novelia.cc/forum/67eead898f8151329eaeebf9")
        listOf(Triple("旧小说", "syosetu", "n2544jm"), Triple("旧短篇", "pixiv", "s20401120"),
            Triple("新小说", "kakuyomu", "16818792440198448037")).forEach { (label, provider, id) ->
            var offset = Offset.Zero
            compose.runOnIdle {
                val view = textView()
                val text = view.text as Spanned
                val row = text.getSpans(0, text.length, io.noties.markwon.ext.tables.TableRowSpan::class.java)
                    .single { it.findLayoutForHorizontalOffset(0)?.text?.toString() == label }
                val line = view.layout.getLineForOffset(text.getSpanStart(row))
                offset = Offset(view.totalPaddingLeft + 16 * view.resources.displayMetrics.density,
                    view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f)
            }
            compose.onNodeWithTag("site-markdown").performTouchInput { click(offset) }
            compose.onNodeWithText("站内书籍").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(provider, nav.currentBackStackEntry?.arguments?.getString("provider"))
                assertEquals(id, nav.currentBackStackEntry?.arguments?.getString("id"))
                nav.popBackStack()
            }
            compose.waitForIdle()
        }
    }

    @Test fun markdownImageLongPressOpensTheIllustrationViewerAndCanZoom() {
        val context = instrumentation.targetContext
        val file = File.createTempFile("markdown-image-", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(360, 160, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(79, 178, 51))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            show("正文图片\n\n![测试图片](${file.toURI()})\n\n长按图片查看", localImageFixture = true)
            compose.waitUntil(10_000) {
                val text = textView().text as Spanned
                text.getSpans(0, text.length, AsyncDrawableSpan::class.java).singleOrNull()?.drawable?.hasResult() == true
            }
            compose.waitForIdle()
            var offset = Offset.Zero
            val touches = mutableListOf<String>()
            compose.runOnIdle {
                val view = textView()
                val text = view.text as Spanned
                val image = text.getSpans(0, text.length, AsyncDrawableSpan::class.java).single()
                val start = text.getSpanStart(image)
                val line = view.layout.getLineForOffset(start)
                offset = Offset(view.totalPaddingLeft + view.layout.getPrimaryHorizontal(start) + image.drawable.bounds.width() * .75f,
                    view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f)
                touches += "image=$start..${text.getSpanEnd(image)}, bounds=${image.drawable.bounds}, view=${view.width}x${view.height}, point=$offset"
                val callback = view.openImage
                view.openImage = { touches += "opened=$it"; callback?.invoke(it) }
                view.setOnTouchListener { _, event ->
                    touches += "event=${event.action}, x=${event.x}, y=${event.y}, offset=${textOffsetAt(view, event)}"
                    false
                }
            }
            screenshot("site-markdown-image-before-touch")
            // Native View.postDelayed uses the real uptime clock, unlike Compose's synthetic
            // longClick timestamps. Hold the pointer across dispatches until the timer fires.
            compose.onNodeWithTag("site-markdown").performTouchInput { down(offset) }
            try {
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithTag("illustration-viewport").fetchSemanticsNodes().isNotEmpty()
                }
            } catch (e: Throwable) {
                throw AssertionError(touches.joinToString("\n"), e)
            } finally {
                compose.onNodeWithTag("site-markdown").performTouchInput { up() }
            }
            compose.onNodeWithTag("illustration-viewport").assertIsDisplayed()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("放大插图").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
            }
            compose.onNodeWithContentDescription("放大插图").performClick()
            compose.onNodeWithTag("illustration-viewport").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "150%"))
            compose.onNodeWithContentDescription("关闭插图").performClick()
            compose.onNodeWithTag("illustration-viewport").assertDoesNotExist()
        } finally { file.delete() }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
