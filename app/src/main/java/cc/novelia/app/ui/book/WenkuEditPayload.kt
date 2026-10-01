package cc.novelia.app.ui.book

import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 冲突检查和实际请求共用相同的可编辑字段，排除阅读量、收藏状态等只读或账号字段。
 * 增加编辑能力时同步扩展此白名单，避免检查的快照与发送的内容不一致。
 */
internal fun WenkuDetail.editablePayload(): JsonObject = JsonObject(
    appJson.encodeToJsonElement(WenkuDetail.serializer(), this).jsonObject.filterKeys {
        it in setOf("title", "titleZh", "cover", "authors", "artists", "level", "introduction", "keywords", "volumes")
    }
)
