package cc.novelia.app.data.model

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable data class ForumRuleBlock(val text: String, val list: Boolean = false, val heading: Boolean = false)
@Serializable data class ForumCommunityRules(
    val blocks: List<ForumRuleBlock>, val entryPath: String = "", val commitSha: String = ""
)

/** 只解析原站静态模板中的文字与列表，在原生控件展示，不执行网页或模板脚本。 */
internal object ForumCommunityRulesParser {
    private val entry = Regex("/assets/index-[A-Za-z0-9_-]+\\.js")
    private val commit = Regex("commitSha\\s*:\\s*[`\"']([a-f0-9]{40})[`\"']")
    fun entryPath(html: String): String = Jsoup.parse(html).select("script[type=module][src]")
        .map { it.attr("src") }.single { entry.matches(it) }
    fun commitSha(bundle: String): String = commit.findAll(bundle).map { it.groupValues[1] }.toSet().single()

    fun parse(source: String): List<ForumRuleBlock> {
        require(source.length <= 100_000) { "守则内容过大" }
        val template = Regex("<template>([\\s\\S]*?)</template>").find(source)?.groupValues?.get(1)
            ?: error("无法识别原站守则模板")
        val section = Jsoup.parseBodyFragment(template).select("section").single()
        // 无法完整解释的新结构不得悄悄丢弃，否则可能漏掉新增守则。
        require(section.select("script, style").isEmpty())
        require(section.ownText().isBlank())
        val blocks = section.children().map { element ->
            when(element.normalName()) {
                "p", "h1", "h2", "h3" -> {
                    require(element.getAllElements().drop(1).all { it.normalName() in setOf("span", "strong", "em", "b", "i", "routerlink", "a", "br") })
                    ForumRuleBlock(element.text().replace(Regex("(?<=[\\p{IsHan}，。「」]) +(?=[\\p{IsHan}，。、「」])"), ""),
                        heading = element.normalName().startsWith("h") || element.hasClass("font-medium"))
                }
                "ul", "ol" -> {
                    require(element.ownText().isBlank())
                    require(element.children().all { it.normalName() == "li" })
                    require(element.select("li *").all { it.normalName() in setOf("span", "strong", "em", "b", "i", "a", "br") })
                    ForumRuleBlock(element.children().mapIndexed { index, li ->
                        "${if(element.normalName() == "ol") "${index + 1}." else "•"} ${li.text()}"
                    }.joinToString("\n"), list = true)
                }
                else -> error("原站守则结构已变化")
            }
        }
        require(blocks.size in 3..60 && blocks.all { it.text.isNotBlank() && "{{" !in it.text } && blocks.sumOf { it.text.length } in 100..30_000)
        return blocks
    }
}

/** 2026-09-30 部署版本的文字副本，首次打开且离线时也可阅读。 */
internal val bundledForumCommunityRules = ForumCommunityRules(listOf(
    ForumRuleBlock("一般违规行为会受到记分处罚，100 天内处罚累计达到 3 分，账号将转为受限状态。受限用户与新注册用户权限相同，无法发布或编辑帖子、评论。账号受限后不会自动解除。处罚原因、依据和分值可在「处罚记录」中查看。"),
    ForumRuleBlock("以下行为会受到记分处罚：", heading = true),
    ForumRuleBlock("• 侮辱、骚扰、攻击他人。\n• 刷屏、灌水、重复发布或持续发表无关内容。\n• 其他破坏正常讨论秩序的行为。", list = true),
    ForumRuleBlock("严重违规将直接封禁账号，包括："),
    ForumRuleBlock("• 以营利为目的，发布广告或其他恶意推广内容。\n• 发布违法内容、暴力威胁，或泄露、传播他人隐私。\n• 规避处罚、批量刷屏，或持续、恶意破坏社区。\n• 宣扬、支持、美化或为法西斯主义及其侵略行为辩护，否认、美化侵略战争或战败历史。", list = true),
    ForumRuleBlock("如果认为处罚或封禁有误，请联系管理员申请复核。社区守则无法穷尽所有不当行为，最终解释权归管理员所有。")
))
