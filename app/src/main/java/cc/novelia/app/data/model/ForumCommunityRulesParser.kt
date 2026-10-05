package cc.novelia.app.data.model

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** 仅解释已知的静态文字和权限数据，不执行 Vue 或 JavaScript；未知结构保留上次缓存。 */
internal object ForumCommunityRulesParser {
    private val entry = Regex("/assets/index-[A-Za-z0-9_-]+\\.js")
    private val commit = Regex("commitSha\\s*:\\s*[`\"']([a-f0-9]{40})[`\"']")
    private val inlineTags = setOf("span", "strong", "em", "b", "i", "routerlink", "a", "br")
    private val script = Regex("""<script setup lang="ts">([\s\S]*?)</script>""")
    // Android 使用 ICU 正则，字面量的闭合括号也必须转义，不能依赖 JVM 的宽松语法。
    private val permissionData = Regex("""\A\s*(?:import\s+\{[^;]+\}\s+from\s+'[^']+';\s*)*const\s+permissions\s*=\s*\[([\s\S]*?)\];\s*\z""")
    private val permissionRow = Regex("""\{\s*site:\s*'([^'\\\r\n]{1,100})'\s*,\s*name:\s*'([^'\\\r\n]{1,200})'\s*,\s*allowed:\s*\[(true|false)\s*,\s*(true|false)\s*,\s*(true|false)\s*\]\s*\}""")

    fun entryPath(html: String): String = Jsoup.parse(html).select("script[type=module][src]")
        .map { it.attr("src") }.single { entry.matches(it) }
    fun commitSha(bundle: String): String = commit.findAll(bundle).map { it.groupValues[1] }.toSet().single()

    fun parse(source: String): List<ForumRuleBlock> {
        require(source.length <= 100_000) { "守则内容过大" }
        val template = Regex("<template>([\\s\\S]*?)</template>").findAll(source).single().groupValues[1]
        val section = Jsoup.parseBodyFragment(template).select("section").single()
        require(section.select("script, style").isEmpty() && section.ownText().isBlank())
        val blocks = section.children().map { element ->
            when(element.normalName()) {
                "p", "h1", "h2", "h3" -> textBlock(element)
                "header" -> {
                    require(element.ownText().isBlank() && element.children().size == 1 && element.child(0).normalName() == "h1")
                    textBlock(element.child(0))
                }
                "ul", "ol" -> {
                    require(element.ownText().isBlank() && element.children().all { it.normalName() == "li" })
                    require(element.select("li *").all { it.normalName() in inlineTags })
                    ForumRuleBlock(element.children().mapIndexed { index, li ->
                        "${if(element.normalName() == "ol") "${index + 1}." else "•"} ${text(li)}"
                    }.joinToString("\n"), list = true)
                }
                "div" -> parsePermissionTable(element, source).let { ForumRuleBlock(it.headers.joinToString(" · "), table = it) }
                else -> error("原站守则结构已变化")
            }
        }
        require(blocks.size in 3..60 && blocks.all { it.text.isNotBlank() && "{{" !in it.text } && blocks.sumOf { it.text.length } in 100..30_000)
        return blocks
    }

    private fun text(element: Element) = element.text().replace(Regex("(?<=[\\p{IsHan}，。「」]) +(?=[\\p{IsHan}，。、「」])"), "")
    private fun textBlock(element: Element): ForumRuleBlock {
        require(element.getAllElements().drop(1).all { it.normalName() in inlineTags })
        return ForumRuleBlock(text(element), heading = element.normalName().startsWith("h") || element.hasClass("font-medium"))
    }

    private fun parsePermissionTable(container: Element, source: String): ForumRuleTable {
        require(container.ownText().isBlank() && container.children().size == 1 && container.child(0).normalName() == "table")
        val table = container.child(0)
        require(table.children().map { it.normalName() } == listOf("thead", "tbody"))
        val head = table.child(0); val body = table.child(1)
        require(head.children().size == 1 && head.child(0).normalName() == "tr")
        val headers = head.child(0).children()
        require(headers.size == 5 && headers.all { it.normalName() == "th" && it.getAllElements().drop(1).all { child -> child.normalName() == "br" } })
        require(body.children().size == 1 && body.child(0).normalName() == "tr")
        val row = body.child(0)
        require(row.attr("v-for") == "permission in permissions" && row.attr(":key") == "permission.name")
        val cells = row.children()
        require(cells.map { it.normalName() } == listOf("td", "th", "td"))
        require(cells[0].children().isEmpty() && cells[0].text() == "{{ permission.site }}")
        require(cells[1].children().isEmpty() && cells[1].text() == "{{ permission.name }}")
        val values = cells[2]
        require(values.attr("v-for") == "(allowed, index) in permission.allowed" && values.attr(":key") == "index")
        require(values.children().size == 1 && values.child(0).normalName() == "span")
        val span = values.child(0)
        require(span.attr(":aria-label") == "allowed ? '允许' : '不允许'")
        require(span.children().map { it.normalName() } == listOf("checkoutlined", "closeoutlined"))
        require(span.child(0).attr("v-if") == "allowed" && span.child(1).hasAttr("v-else") && span.child(1).attr("v-else").isEmpty())
        // 每个动态绑定必须有明确解释，防止新增条件或变更图标含义时误报权限。
        val expected = mapOf(row to setOf("v-for", ":key"), values to setOf("v-for", ":key"),
            span to setOf(":aria-label"), span.child(0) to setOf("v-if"), span.child(1) to setOf("v-else"))
        table.getAllElements().forEach { element ->
            require(element.attributes().filter { it.key.startsWith(":") || it.key.startsWith("v-") || it.key.startsWith("@") }
                .map { it.key }.toSet() == expected[element].orEmpty())
            if(element !in headers && element != cells[0] && element != cells[1]) require(element.ownText().isBlank())
        }
        require(span.children().all { it.children().isEmpty() })
        val raw = permissionData.matchEntire(script.findAll(source).single().groupValues[1])?.groupValues?.get(1)
            ?: error("无法识别原站权限数据")
        val matches = permissionRow.findAll(raw).toList()
        require(matches.size in 1..50)
        var end = 0
        val permissions = matches.mapIndexed { index, match ->
            val separator = raw.substring(end, match.range.first)
            require(if(index == 0) separator.isBlank() else separator.matches(Regex("\\s*,\\s*")))
            end = match.range.last + 1
            ForumRulePermission(match.groupValues[1], match.groupValues[2], (3..5).map { match.groupValues[it] == "true" })
        }
        require(raw.substring(end).matches(Regex("\\s*,?\\s*")) && permissions.map { it.site to it.operation }.distinct().size == permissions.size)
        require(permissions.all { it.site.isNotBlank() && it.operation.isNotBlank() && "{{" !in it.site + it.operation })
        return ForumRuleTable(headers.map { it.text() }.also { require(it.all(String::isNotBlank)) }, permissions)
    }
}
