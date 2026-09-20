package cc.novelia.app.ui.web

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.ui.community.ArticleScreen
import cc.novelia.app.ui.markdown.DetailsNode
import cc.novelia.app.ui.markdown.DetailsToggleSpan
import cc.novelia.app.ui.markdown.HeadingAnchorSpan
import cc.novelia.app.ui.markdown.MarkdownAnchors
import cc.novelia.app.ui.markdown.SpoilerTextView
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import cc.novelia.app.ui.web.SiteWebScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SiteWebNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private lateinit var controller: AppController
    private lateinit var nav: NavHostController

    private fun show(liveArticles: Boolean = false) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        compose.setContent {
            NoveliaTheme("light") {
                root = LocalView.current
                nav = rememberNavController()
                val scope = rememberCoroutineScope()
                controller = remember { AppController(app, nav, scope, SnackbarHostState()) }
                NavHost(nav, startDestination = "sample") {
                    composable("sample") { Text("起点") }
                    composable("article/{id}") {
                        if (liveArticles) ArticleScreen(controller, it.arguments!!.getString("id")!!)
                        else Text("帖子 ${it.arguments?.getString("id")}")
                    }
                    composable("book/{provider}/{id}") { Text("书籍 ${it.arguments?.getString("id")}") }
                    composable("reader/{provider}/{id}/{chapter}") { Text("章节 ${it.arguments?.getString("chapter")}") }
                    composable("web?url={url}") { SiteWebScreen(controller, it.arguments?.getString("url").orEmpty()) }
                }
            }
        }
    }

    private fun webView(): WebView? {
        fun find(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(root.rootView)
    }

    private fun markdownView(): SpoilerTextView? {
        fun find(view: View): SpoilerTextView? {
            if (view is SpoilerTextView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(root.rootView)
    }

    @Test fun liveTutorialDirectoryLinksScrollInsideTheNativeArticle() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSite") == "true")
        show(liveArticles = true)
        val page = "https://n.novelia.cc/forum/64f3d63f794cbb1321145c07"
        compose.runOnIdle { controller.openMarkdownLink(page) }
        compose.waitUntil(30_000) { markdownView() != null }
        val initialEntry = nav.currentBackStackEntry!!.id
        lateinit var links: List<Pair<io.noties.markwon.core.spans.LinkSpan, String>>
        compose.runOnIdle {
            val view = markdownView()!!
            val text = view.text as android.text.Spanned
            links = text.getSpans(0, text.length, io.noties.markwon.core.spans.LinkSpan::class.java).mapNotNull { span ->
                cc.novelia.app.data.markdown.MarkdownLinks.localFragment(span.link, page)?.let { span to it }
            }
            assertTrue("原站教程应有多个页内链接", links.size >= 5)
        }
        links.forEach { (link, fragment) ->
            compose.runOnIdle { link.onClick(markdownView()!!) }
            compose.waitForIdle()
            val viewport = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                assertEquals("页内跳转不应新增页面：$fragment", initialEntry, nav.currentBackStackEntry!!.id)
                assertNull(webView())
                val view = markdownView()!!
                val text = view.text as android.text.Spanned
                val heading = MarkdownAnchors(view.tag as org.commonmark.node.Node).find(fragment)!!
                val marker = text.getSpans(0, text.length, HeadingAnchorSpan::class.java).single { it.heading === heading }
                val location = IntArray(2).also(view::getLocationOnScreen)
                val top = location[1] + view.totalPaddingTop + view.layout.getLineTop(view.layout.getLineForOffset(text.getSpanStart(marker)))
                assertTrue("$fragment: top=$top viewport=$viewport", top >= viewport.top && top < viewport.top + 150)
            }
        }
    }

    @Test fun liveOriginalSiteKeepsRequestedPage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSite") == "true")
        show()
        val url = "https://n.novelia.cc/forum-edit"
        compose.runOnIdle { controller.openMarkdownLink(url) }
        compose.waitUntil(30_000) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val web = webView()
                ready = web != null && web.progress == 100
            }
            ready
        }
        compose.runOnIdle {
            val web = webView()!!
            assertEquals("route=${nav.currentBackStackEntry?.arguments}; title=${web.title}; original=${web.originalUrl}", url, web.url)
            assertTrue("title=${web.title}; url=${web.url}", web.title.orEmpty().contains("发布文章"))
        }
    }

    @Test fun liveTutorialAnchorOpensItsArticleInsteadOfTheHomepage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSite") == "true")
        show()
        val page = "https://n.novelia.cc/forum/64f3d63f794cbb1321145c07"
        val fragment = "#%E5%A6%82%E4%BD%95%E7%94%9F%E6%88%90%E6%9C%BA%E7%BF%BB"
        compose.runOnIdle { controller.openMarkdownLink(fragment, page) }
        val heading = java.util.concurrent.atomic.AtomicReference("")
        compose.waitUntil(45_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView()?.evaluateJavascript("document.querySelector('h1')?.textContent || ''") { heading.set(it) }
            }
            heading.get().contains("使用教程")
        }
        compose.runOnIdle {
            val web = webView()!!
            assertEquals(page + fragment, web.url)
            assertEquals(page + fragment, nav.currentBackStackEntry?.arguments?.getString("url"))
        }
        val anchorTop = java.util.concurrent.atomic.AtomicReference<Double?>(null)
        compose.waitUntil(5_000) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView()?.evaluateJavascript("document.getElementById(location.hash.slice(1))?.getBoundingClientRect().top ?? null") { anchorTop.set(it.toDoubleOrNull()) }
            }
            anchorTop.get()?.let { it in -1.0..150.0 } == true
        }
    }

    @Test fun detailLinksPreserveEachPreviousEntry() {
        show()
        val links = listOf("/forum/aaa111", "/forum/bbb222", "/wenku/ccc333", "/wenku/ddd444", "/novel/syosetu/n1234/1", "/novel/syosetu/n1234/2")
        val entries = mutableListOf<String>()
        links.forEach { url -> compose.runOnIdle { controller.openMarkdownLink(url); entries += nav.currentBackStackEntry!!.id } }
        entries.dropLast(1).asReversed().forEach { id -> compose.runOnIdle { controller.back(); assertEquals(id, nav.currentBackStackEntry!!.id) } }
        compose.runOnIdle { controller.back(); assertEquals("sample", nav.currentDestination?.route) }
    }

    @Test fun liveExampleRetainsItsLoadedImageAcrossFoldToggles() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSite") == "true")
        show(liveArticles = true)
        compose.runOnIdle { controller.openMarkdownLink("https://n.novelia.cc/forum/6a4ba6ed447e2e413279011d") }
        fun markdown(): SpoilerTextView? {
            fun find(view: View): SpoilerTextView? {
                if (view is SpoilerTextView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            return find(root.rootView)
        }
        compose.waitUntil(30_000) { markdown() != null }
        lateinit var node: DetailsNode
        lateinit var image: io.noties.markwon.image.AsyncDrawableSpan
        compose.runOnIdle {
            val view = markdown()!!
            val text = view.text as android.text.Spanned
            val fold = text.getSpans(0, text.length, DetailsToggleSpan::class.java).first()
            node = fold.node
            fold.onClick(view)
        }
        compose.waitUntil(30_000) {
            val text = markdown()!!.text as android.text.Spanned
            text.getSpans(0, text.length, io.noties.markwon.image.AsyncDrawableSpan::class.java).firstOrNull()?.drawable?.hasResult() == true
        }
        compose.runOnIdle {
            val view = markdown()!!
            val text = view.text as android.text.Spanned
            image = text.getSpans(0, text.length, io.noties.markwon.image.AsyncDrawableSpan::class.java).first()
            assertTrue(image.drawable.bounds.height() > 1)
            DetailsToggleSpan(node).onClick(view)
            DetailsToggleSpan(node).onClick(view)
            val restored = view.text as android.text.Spanned
            assertSame(image, restored.getSpans(0, restored.length, io.noties.markwon.image.AsyncDrawableSpan::class.java).first())
            assertTrue(image.drawable.hasResult())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, markdown()!!.scrollY) }
    }
}
