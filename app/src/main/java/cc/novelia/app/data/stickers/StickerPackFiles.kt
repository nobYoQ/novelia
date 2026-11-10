package cc.novelia.app.data.stickers

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
data class StickerPackManifest(
    val format: String = "novelia-stickers",
    val version: Int = 1,
    val id: String = "",
    val title: String,
    val companionName: String = title,
    val heartColor: String = "#E15C86",
    val artwork: Map<String, String> = emptyMap(),
)

data class LocalStickerPack(val manifest: StickerPackManifest, val directory: File) {
    val id get() = manifest.id
    fun images(): Map<StickerSlot, File> = manifest.artwork.mapKeys { (key, _) -> StickerSlot.entries.first { it.key == key } }
        .mapValues { (_, path) -> stickerFile(directory, path) }
}

data class StickerDraft(val id: String, val originalId: String?, val pack: LocalStickerPack)

data class StickerLibrary(
    val packs: List<LocalStickerPack> = emptyList(),
    val drafts: List<StickerDraft> = emptyList(),
    val ready: Boolean = false,
    val error: String? = null,
)

internal const val STICKER_IMAGE_LIMIT = 8L * 1024 * 1024
internal const val STICKER_PACK_LIMIT = 64L * 1024 * 1024
internal const val STICKER_MANIFEST_LIMIT = 64L * 1024
internal val LOCAL_STICKER_ID = Regex("local-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
internal val STICKER_DRAFT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun StickerPackManifest.validate(requireNeutral: Boolean = true) {
    require(format == "novelia-stickers" && version == 1) { "不支持的贴纸包格式或版本" }
    require(title.isNotBlank() && title.length <= 40 && title.none(Char::isISOControl)) { "贴纸集名称需为 1–40 个字符" }
    require(companionName.isNotBlank() && companionName.length <= 24 && companionName.none(Char::isISOControl)) { "搭子名称需为 1–24 个字符" }
    require(Regex("#[0-9a-fA-F]{6}").matches(heartColor)) { "爱心颜色请使用 #RRGGBB 格式" }
    require(artwork.keys.all { key -> StickerSlot.entries.any { it.key == key } }) { "贴纸包包含未知表情" }
    require(!requireNeutral || StickerSlot.Neutral.key in artwork) { "请至少添加一张常态图片" }
    artwork.values.forEach(::requireStickerPath)
}

internal fun requireStickerPath(path: String) {
    require(path.length in 1..240 && path.none { it.isISOControl() || it == '\\' || it == ':' } &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) { "贴纸包包含不安全的文件路径" }
}

internal fun stickerFile(directory: File, path: String): File {
    requireStickerPath(path)
    val root = directory.canonicalFile
    return File(root, path).canonicalFile.also {
        require(it.toPath().startsWith(root.toPath()) && it != root) { "贴纸文件超出存储目录" }
    }
}
