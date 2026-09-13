package cc.novelia.app.ui

import org.commonmark.node.*
import java.net.URI
import java.util.Locale

/** Match markdown-it-anchor's default heading text and duplicate-name rules. */
internal class MarkdownAnchors(document: Node) {
    private val headings = linkedMapOf<String, Heading>()

    init {
        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val title = StringBuilder()
                heading.accept(object : AbstractVisitor() {
                    override fun visit(text: Text) { title.append(text.literal) }
                    override fun visit(code: Code) { title.append(code.literal) }
                    override fun visit(html: HtmlInline) { title.append(html.literal) }
                    override fun visit(image: Image) = Unit // Image alt text is not part of the site's heading ID.
                })
                val slug = title.toString().trim { it.isWhitespace() || it == '\uFEFF' }
                    .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{Z}\\uFEFF]+"), "-")
                var unique = slug
                var suffix = 1
                while (unique in headings) unique = "$slug-${suffix++}"
                headings[unique] = heading
            }
        })
    }

    fun find(fragment: String): Heading? = headings[fragment] ?: runCatching {
        // Also accept links copied from the site's percent-encoded DOM IDs.
        headings[URI("#$fragment").fragment]
    }.getOrNull()
}

/** A non-drawing marker: the standard heading span still controls appearance. */
internal class HeadingAnchorSpan(val heading: Heading)
