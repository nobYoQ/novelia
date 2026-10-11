package cc.novelia.app.ui.community

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.community.Article
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.LocalReducedMotion
import cc.novelia.app.ui.theme.NoveliaTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticleEditorLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sourceEditorFitsKeyboardAndSurvivesManualPreview() {
        val app = compose.activity.application as NoveliaApplication
        val previous = app.store.state.value
        val draftKey = "article:layout-test"
        val content = "# 编辑体验\n\n" + (1..70).joinToString("\n") { "第 $it 行：正文需要能够上下滑动。" }
        try {
            compose.runOnUiThread {
                app.store.update { it.copy(drafts = it.drafts - draftKey) }
                compose.activity.setContent {
                    val arguments = InstrumentationRegistry.getArguments()
                    CompositionLocalProvider(LocalReducedMotion provides (arguments.getString("reducedMotion") == "true")) {
                    NoveliaTheme(arguments.getString("theme") ?: "light") {
                        val c = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                        ArticleEditor(c, Article(id = "layout-test", title = "编辑体验测试", content = content))
                    }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.activity.hasWindowFocus() }
            compose.onNodeWithTag("article-body").performScrollTo().performTouchInput { click() }
            assertEditorAboveKeyboard("article-body")
            screenshot("article-source-keyboard")
            compose.onNodeWithTag("article-body").performTouchInput { swipeUp() }
            compose.onNodeWithText("预览").performClick()
            compose.onNodeWithText("编辑").performClick()
            compose.onNodeWithTag("article-body").assertTextContains(content)
                .performScrollTo().performTouchInput { click() }
            assertEditorAboveKeyboard("article-body")
        } finally {
            compose.runOnIdle {
                app.store.update { it.copy(drafts = (it.drafts - draftKey) + previous.drafts.filterKeys { key -> key == draftKey }) }
            }
        }
    }

    private fun assertEditorAboveKeyboard(tag: String) {
        val decor = compose.activity.window.decorView
        try { compose.waitUntil(10_000) {
            (ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0
        } } catch (failure: Throwable) {
            screenshot("article-keyboard-failure")
            throw AssertionError("windowFocus=${compose.activity.hasWindowFocus()}; ${compose.onNodeWithTag(tag).printToString()}", failure)
        }
        compose.waitUntil(10_000) {
            val ime = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val field = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
            val toolbar = compose.onNodeWithText("编辑帖子").fetchSemanticsNode().boundsInWindow
            val formatting = compose.onNodeWithContentDescription("插入粗体").fetchSemanticsNode().boundsInWindow
            val measured = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val measuredHeight = with(compose.density) { (measured.bottom - measured.top).toPx() }
            formatting.top >= toolbar.bottom && formatting.bottom <= field.top + 2 && field.bottom <= decor.height - ime + 2 && field.height >= measuredHeight - 2
        }
        assertTrue(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.height > 100)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "$name.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
