package cc.novelia.app.ui.book

import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val coverExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
private val kanaInTitle = Regex("[ぁ-ゖァ-ヺ]")

internal fun wenkuCoverError(cover: String?): String? {
    if (cover.isNullOrBlank()) return "请填写作品封面链接"
    val url = cover.toHttpUrlOrNull()
    if (!cover.startsWith("https://", ignoreCase = true) || url?.isHttps != true) return "请使用有效的 HTTPS 封面链接"
    if (url.encodedPath.substringAfterLast('.', "").lowercase() !in coverExtensions) {
        return "封面链接需要以 JPG、JPEG、PNG、WebP、GIF 或 AVIF 结尾"
    }
    return null
}

internal fun WenkuDetail.formError(canReadAdult: Boolean): String? = when {
    title.isBlank() || titleZh.isBlank() -> "原文标题和中文标题为必填项"
    title.length > 80 || titleZh.length > 80 -> "原文标题和中文标题各限 80 个字符"
    kanaInTitle.containsMatchIn(titleZh) -> "中文标题不能包含日文假名"
    introduction.length > 1000 -> "作品简介最多 1000 个字符"
    !canReadAdult && level in listOf("成人向", "成人向女") -> "当前账号不能选择成人向分类"
    else -> wenkuCoverError(cover)
}

/**
 * 冲突检查和实际请求共用相同的可编辑字段，排除阅读量、收藏状态等只读或账号字段。
 * 增加编辑能力时同步扩展此白名单，避免检查的快照与发送的内容不一致。
 */
internal fun WenkuDetail.editablePayload(): JsonObject = JsonObject(
    appJson.encodeToJsonElement(WenkuDetail.serializer(), this).jsonObject.filterKeys {
        it in setOf("title", "titleZh", "cover", "authors", "artists", "level", "introduction", "keywords", "volumes")
    }
)
