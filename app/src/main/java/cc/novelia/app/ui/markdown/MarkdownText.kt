@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package cc.novelia.app.ui.markdown

import android.text.Spanned
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.ui.components.IllustrationViewer
import cc.novelia.app.ui.navigation.AppController
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.destination.ImageDestinationProcessor
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.commonmark.node.Heading

@Composable internal fun rememberMarkdownRenderer(c: AppController): Markwon {
    val context = LocalContext.current
    return remember(context, c) {
        Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(ImagesPlugin.create())
            .usePlugin(SpoilerPlugin()).usePlugin(SiteMarkdownPlugin(context))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val markdown = view as? SpoilerTextView
                        if (markdown?.openAnchor?.invoke(link) != true) c.openMarkdownLink(link, markdown?.documentUrl)
                    }
                    builder.imageDestinationProcessor(object : ImageDestinationProcessor() {
                        override fun process(destination: String) = MarkdownLinks.resolve(destination) ?: destination
                    })
                }
            }).build()
    }
}

@Composable fun MarkdownText(c: AppController, text: String, modifier: Modifier = Modifier, renderer: Markwon? = null,
    documentUrl: String? = null, onAnchorScroll: (suspend (Int) -> Unit)? = null) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val markwon = renderer ?: rememberMarkdownRenderer(c)
    val document = remember(markwon, text) { try { markwon.parse(text) } catch (_: RuntimeException) { null } }
    val anchors = remember(document) { document?.let(::MarkdownAnchors) }
    var anchorRequest by remember(document) { mutableStateOf<AnchorRequest?>(null) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scrollToAnchor by rememberUpdatedState(onAnchorScroll)
    LaunchedEffect(document, anchorRequest) {
        val request = anchorRequest ?: return@LaunchedEffect
        val view = request.view
        var ancestor = request.heading?.parent
        var expanded = false
        while (ancestor != null) {
            if (ancestor is DetailsNode && !ancestor.expanded) { ancestor.expanded = true; expanded = true }
            ancestor = ancestor.parent
        }
        if (expanded) view.refreshMarkdown?.invoke()
        view.awaitMarkdownLayout()
        val rendered = view.text as? Spanned ?: return@LaunchedEffect
        val offset = if (request.heading == null) 0 else {
            val marker = rendered.getSpans(0, rendered.length, HeadingAnchorSpan::class.java)
                .firstOrNull { it.heading === request.heading } ?: return@LaunchedEffect
            rendered.getSpanStart(marker)
        }
        val layout = view.layout ?: return@LaunchedEffect
        val line = layout.getLineForOffset(offset)
        val top = view.totalPaddingTop + layout.getLineTop(line)
        val scroll = scrollToAnchor
        if (scroll != null) scroll(top)
        else bringIntoView.bringIntoView(Rect(0f, top.toFloat(), view.width.toFloat(), (view.totalPaddingTop + layout.getLineBottom(line)).toFloat()))
    }
    val movement = remember { TableAwareMovementMethod.wrap(SpoilerMovementMethod()) }
    var expandedImage by remember(text) { mutableStateOf<String?>(null) }
    AndroidView(factory = { SpoilerTextView(it).apply {
        textSize = 16f
        setLineSpacing(6f, 1.1f)
        setTextIsSelectable(true)
    } }, modifier = modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView), update = { view ->
        view.documentUrl = documentUrl
        view.openAnchor = { link ->
            val fragment = MarkdownLinks.localFragment(link, documentUrl)
            val heading = fragment?.takeIf { it.isNotEmpty() }?.let { anchors?.find(it) }
            if (fragment != null && (fragment.isEmpty() || heading != null)) {
                anchorRequest = AnchorRequest(view, heading)
                true
            } else false
        }
        if (view.currentTextColor != color) view.setTextColor(color)
        if (view.linkTextColors.defaultColor != linkColor) view.setLinkTextColor(linkColor)
        view.refreshMarkdown = {
            try {
                if (document == null) view.text = text
                else markwon.setParsedMarkdown(view, markwon.render(document))
            } catch (_: RuntimeException) {
                // Parsing and plugin rendering are separate failure boundaries.
                view.text = text
            }
            view.movementMethod = movement
        }
        view.openImage = { expandedImage = it }
        val renderKey = document ?: text
        if (view.tag !== renderKey) {
            view.refreshMarkdown?.invoke()
            view.tag = renderKey
        }
        view.movementMethod = movement
    })
    expandedImage?.let { IllustrationViewer(it) { expandedImage = null } }
}

private class AnchorRequest(val view: SpoilerTextView, val heading: Heading?)

private suspend fun View.awaitMarkdownLayout() {
    if (isLaidOut && !isLayoutRequested) return
    suspendCancellableCoroutine { continuation ->
        // Leave AndroidView/Compose's layout pass before a scroll can request remeasurement.
        val resumeAfterLayout = Runnable { if (continuation.isActive) continuation.resume(Unit) }
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                removeOnLayoutChangeListener(this)
                post(resumeAfterLayout)
            }
        }
        addOnLayoutChangeListener(listener)
        continuation.invokeOnCancellation {
            removeOnLayoutChangeListener(listener)
            removeCallbacks(resumeAfterLayout)
        }
    }
}
