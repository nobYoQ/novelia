package cc.novelia.app.data.network

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WenkuOutline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 与网站查重一致，优先取第一段连续的假名或汉字。
private val titleKeyword = Regex("""[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9faf]+""")

internal suspend fun NoveliaApi.similarWenku(
    title: String, includeAdult: Boolean, binding: SessionBinding? = session?.capture(),
): List<WenkuOutline> = coroutineScope {
    val query = titleKeyword.find(title)?.value ?: title.trim()
    require(query.isNotEmpty()) { "请先填写原文标题" }
    val levels = if (includeAdult) listOf(0, 4, 5, 6) else listOf(0, 4)
    levels.map { level ->
        async { wenkuList(0, query, level, pageSize = 6, binding = binding).items }
    }.awaitAll().flatten().distinctBy { it.id }
}

internal suspend fun NoveliaApi.updateWebBookText(ref: BookRef, title: String, introduction: String, binding: SessionBinding?) {
    val body = buildJsonObject {
        put("title", title)
        put("introduction", introduction)
        // 此表单只编辑书名和简介。空映射使服务端保留目录中每个章节的当前译名。
        put("toc", JsonObject(emptyMap()))
    }
    request("PUT", "novel/${ref.key}/translation", body.toString(), binding = binding)
}
