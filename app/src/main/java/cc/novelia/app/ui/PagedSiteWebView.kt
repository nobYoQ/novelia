package cc.novelia.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/** Original-site pages use the same release-to-page gesture as native screens. */
internal class PagedSiteWebView(context: Context) : WebView(context) {
    var onPageAvailabilityChanged: (Boolean, Boolean) -> Unit = { _, _ -> }
    private var availablePages: Pair<Boolean, Boolean>? = null
    var eInkMode = false
        set(value) {
            if(field == value) return
            field = value
            overScrollMode = if(value) OVER_SCROLL_NEVER else OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = !value
            settings.setSupportZoom(!value)
            applyMotionPreference()
        }
    private var startX = 0f
    private var startY = 0f
    private var paging = false
    private var multiplePointers = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    fun page(direction: Int) { scrollBy(0, (screenPageDistance(height, 48 * resources.displayMetrics.density) * direction).toInt()) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = canScrollVertically(-1) to canScrollVertically(1)
        if(next != availablePages) {
            availablePages = next
            post { onPageAvailabilityChanged(next.first, next.second) }
        }
    }

    fun applyMotionPreference() {
        evaluateJavascript(if(eInkMode) """
            (() => {
                if (document.getElementById('novelia-eink-motion')) return;
                const style = document.createElement('style');
                style.id = 'novelia-eink-motion';
                style.textContent = '*,*::before,*::after { scroll-behavior:auto!important; animation:none!important; transition:none!important; }';
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent() else "(() => { const style = document.getElementById('novelia-eink-motion'); if (style) style.remove(); })();", null)
    }

    // WebView already handles DOM taps and accessibility activation; intercept only drag streams.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!eInkMode) return super.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y; paging = false; multiplePointers = false }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                multiplePointers = multiplePointers || event.pointerCount > 1
                if(!paging && (abs(event.y - startY) > slop || abs(event.x - startX) > slop || multiplePointers)) {
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    paging = true
                }
                if(paging) return true
            }
            MotionEvent.ACTION_UP -> if(paging) {
                val dx = event.x - startX; val dy = event.y - startY
                if(!multiplePointers && maxOf(abs(dx), abs(dy)) >= 24 * resources.displayMetrics.density) {
                    if(abs(dy) >= abs(dx)) page(if(dy < 0) 1 else -1)
                    else scrollBy((screenPageDistance(width, 48 * resources.displayMetrics.density) * if(dx < 0) 1 else -1).toInt(), 0)
                }
                paging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> { paging = false; multiplePointers = false }
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(eInkMode && keyCode in listOf(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP)) {
            if(event.repeatCount == 0) page(if(keyCode == KeyEvent.KEYCODE_PAGE_DOWN) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if(eInkMode && keyCode in listOf(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_PAGE_UP)) true else super.onKeyUp(keyCode, event)
}
