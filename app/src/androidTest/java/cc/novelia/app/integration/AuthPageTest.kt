package cc.novelia.app.integration

import cc.novelia.app.MainActivity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opens the public login form without entering credentials or submitting a form. */
@RunWith(AndroidJUnit4::class)
class AuthPageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun webView(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { webView(view.getChildAt(it)) }
        else -> null
    }

    @Test fun trustedAuthenticationFormIsVisible() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("登录 / 注册").performClick()
        val messages = CopyOnWriteArrayList<String>()
        val formVisible = AtomicBoolean(false)
        lateinit var web: WebView
        compose.runOnIdle {
            web = requireNotNull(webView(compose.activity.window.decorView))
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) messages.add(message.message().take(200))
                    return true
                }
            }
            // Inspect only element visibility, never values, passwords, cookies or page storage.
            WebViewCompat.addWebMessageListener(web, "NoveliaFormTest", setOf("https://auth.novelia.cc")) { _, message, origin, mainFrame, _ ->
                if (!mainFrame && origin.host == "auth.novelia.cc" && message.data == "visible") formVisible.set(true)
            }
            WebViewCompat.addDocumentStartJavaScript(web, """
                var formCheck = setInterval(function() {
                    var count = Array.from(document.querySelectorAll('input')).filter(function(input) {
                        return input.type !== 'hidden' && input.getBoundingClientRect().height > 0;
                    }).length;
                    if (count >= 2) { window.NoveliaFormTest.postMessage('visible'); clearInterval(formCheck); }
                }, 250);
            """.trimIndent(), setOf("https://auth.novelia.cc"))
            web.reload()
        }
        val deadline = System.currentTimeMillis() + 30000
        while (System.currentTimeMillis() < deadline && !formVisible.get()) {
            Thread.sleep(500)
        }
        var diagnostic = ""
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val nativeBounds = "${web.width}x${web.height}, measured=${web.measuredWidth}x${web.measuredHeight}"
            web.evaluateJavascript("JSON.stringify({origin:location.origin,ready:document.readyState,title:document.title,frames:document.querySelectorAll('iframe').length,height:document.querySelector('iframe')?.clientHeight,viewport:innerHeight})") {
                diagnostic = "$it; native=$nativeBounds"
                completed.countDown()
            }
        }
        completed.await(5, TimeUnit.SECONDS)
        val screenshot = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "login.png")
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        assertTrue("Login form not visible: $diagnostic; errors: $messages", formVisible.get() && web.height > 0)
    }
}
