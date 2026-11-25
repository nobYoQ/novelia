package cc.novelia.app.data.network

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.BookRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun NoveliaApi.updateWebBookText(ref: BookRef, title: String, introduction: String, binding: SessionBinding?) {
    val body = buildJsonObject {
        put("title", title)
        put("introduction", introduction)
        // 此表单只编辑书名和简介。空映射使服务端保留目录中每个章节的当前译名。
        put("toc", JsonObject(emptyMap()))
    }
    request("PUT", "novel/${ref.key}/translation", body.toString(), binding = binding)
}
