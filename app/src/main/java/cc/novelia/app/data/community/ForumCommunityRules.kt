package cc.novelia.app.data.community

import kotlinx.serialization.Serializable

@Serializable data class ForumRulePermission(val site: String, val operation: String, val allowed: List<Boolean>)
@Serializable data class ForumRuleTable(val headers: List<String>, val rows: List<ForumRulePermission>)
@Serializable data class ForumRuleBlock(val text: String, val list: Boolean = false, val heading: Boolean = false, val table: ForumRuleTable? = null)
@Serializable data class ForumCommunityRules(
    val blocks: List<ForumRuleBlock>, val entryPath: String = "", val commitSha: String = ""
)

/** 2026-10-04 部署 36f8894 的离线副本；内容与该部署守则解析结果一致。 */
internal val bundledForumCommunityRules = ForumCommunityRules(listOf(
    ForumRuleBlock("社区守则", heading = true),
    ForumRuleBlock("违规处理", heading = true),
    ForumRuleBlock("一般违规行为会受到记分处罚， 100 天内处罚累计达到 3 分，账号将转为受限状态，权限按上述说明限制。账号受限后不会自动解除。处罚原因、依据和分值可在「处罚记录」中查看。"),
    ForumRuleBlock("以下行为会受到记分处罚：", heading = true),
    ForumRuleBlock("• 侮辱、骚扰、攻击他人。\n• 刷屏、灌水、重复发布或持续发表无关内容。\n• 发布网赚盘。\n• 乱改小说元数据。\n• 其他破坏正常讨论秩序的行为。", list = true),
    ForumRuleBlock("严重违规将直接封禁账号，包括："),
    ForumRuleBlock("• 以营利为目的，发布广告或其他恶意推广内容。\n• 发布违法内容、暴力威胁，或泄露、传播他人隐私。\n• 规避处罚、批量刷屏，或持续、恶意破坏社区。\n• 滥用术语表。\n• 宣扬、支持、美化或为法西斯主义及其侵略行为辩护，否认、美化侵略战争或战败历史。", list = true),
    ForumRuleBlock("遇到违反社区守则的行为，不要发评论争吵，直接加群联系管理员处理。", heading = true),
    ForumRuleBlock("如果认为处罚或封禁有误，请联系管理员申请复核。社区守则无法穷尽所有不当行为，最终解释权归管理员所有。"),
    ForumRuleBlock("建政小说处理办法", heading = true),
    ForumRuleBlock("对于以二战或者现实战争为核心内容，涉及法西斯、反战败元素的小说，请用QQ或者Telegram私信管理员，符合标准会屏蔽。不要在论坛发帖，或者在群里讨论。"),
    ForumRuleBlock("不要特地去找这种小说，也不要截图到处传播。不要在机翻站建政。无论你觉得你的观点多么正义，也要挑选说话的场合。冲到电影院大喊1+1=2以至于被保安拖了出去，不是因为大家不认同你的观点，是你是个SB。"),
    ForumRuleBlock("用户权限", heading = true),
    ForumRuleBlock("站点 · 操作 · 普通用户 未满月 · 普通用户 已满月 · 受限用户", table = ForumRuleTable(
        listOf("站点", "操作", "普通用户 未满月", "普通用户 已满月", "受限用户"), listOf(
            ForumRulePermission("论坛", "管理帖子收藏", listOf(true, true, true)),
            ForumRulePermission("论坛", "发表、编辑帖子", listOf(true, true, false)),
            ForumRulePermission("论坛", "发表、编辑评论", listOf(true, true, false)),
            ForumRulePermission("小说", "发表、编辑评论", listOf(false, true, false)),
            ForumRulePermission("小说", "管理小说收藏", listOf(true, true, true)),
            ForumRulePermission("小说", "更新网页小说", listOf(false, true, false)),
            ForumRulePermission("小说", "编辑网页小说", listOf(false, true, false)),
            ForumRulePermission("小说", "创建、编辑文库小说", listOf(false, true, false)),
            ForumRulePermission("小说", "上传文库小说", listOf(true, true, true)),
            ForumRulePermission("小说", "编辑术语表", listOf(false, true, false))
        )))
))
