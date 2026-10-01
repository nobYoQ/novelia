package cc.novelia.app.ui.markdown

import java.net.URI
import java.util.Locale
import org.commonmark.node.*

/** 匹配 markdown-it-anchor 默认的标题文本提取和重名规则。 */
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
                    override fun visit(image: Image) = Unit // 图片替代文本不参与原站标题 ID 的生成。
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
        // 也接受从原站复制的百分号编码 DOM ID 链接。
        headings[URI("#$fragment").fragment]
    }.getOrNull()
}

/** 只标记锚点，不参与绘制；标题外观仍由标准标题 span 控制。 */
internal class HeadingAnchorSpan(val heading: Heading)
