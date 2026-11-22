package cc.novelia.app.sharing

import android.content.Context
import cc.novelia.app.ui.markdown.SiteMarkdownPlugin
import cc.novelia.app.ui.markdown.SpoilerNode
import cc.novelia.app.ui.markdown.configureSpoilerParser
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser

// 图片节选复用论坛语法。表格按行排版，图片用替代文本，折叠内容和剧透保持隐藏。
internal fun renderPostExcerpt(context: Context, markdown: String, linkColor: Int): CharSequence =
    Markwon.builder(context).usePlugin(SiteMarkdownPlugin(context, forumRatings = true))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureParser(builder: Parser.Builder) {
                builder.extensions(listOf(TablesExtension.create()))
                configureSpoilerParser(builder)
            }

            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(linkColor).blockMargin(26).blockQuoteWidth(5).bulletWidth(10)
            }

            override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                builder.on(SpoilerNode::class.java) { visitor, _ -> visitor.builder().append("［剧透］") }
                builder.on(TableRow::class.java) { visitor, node ->
                    visitor.ensureNewLine()
                    visitor.visitChildren(node)
                    visitor.ensureNewLine()
                }
                builder.on(TableCell::class.java) { visitor, node ->
                    if (node.previous != null) visitor.builder().append(" · ")
                    visitor.visitChildren(node)
                }
            }
        }).build().toMarkdown(markdown)
