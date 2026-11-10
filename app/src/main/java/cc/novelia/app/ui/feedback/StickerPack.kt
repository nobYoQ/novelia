package cc.novelia.app.ui.feedback

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import cc.novelia.app.R
import cc.novelia.app.data.model.DEFAULT_STICKER_PACK_ID
import cc.novelia.app.data.stickers.LocalStickerPack as InstalledStickerPack
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/** 页面只表达用途，具体角色和素材由当前贴纸集提供。 */
typealias Sticker = cc.novelia.app.data.stickers.StickerSlot

/**
 * 贴纸集的显示模型，支持内置资源与本机导入的图片。
 * 必须提供常态图，缺少其他表情时使用本集常态图，避免混入另一角色。
 * 复制素材映射，防止注册后被外部修改；图片保留透明边缘和原始宽高比。
 */
class StickerPack(
    val id: String,
    val title: String,
    val companionName: String,
    artwork: Map<Sticker, Int> = emptyMap(),
    val heartColor: Color = Color(0xFFE15C86),
    files: Map<Sticker, File> = emptyMap(),
) {
    private val artwork = artwork.toMap()
    private val files = files.toMap()
    val custom get() = files.isNotEmpty()

    init {
        require(id.isNotBlank() && title.isNotBlank() && companionName.isNotBlank())
        require((Sticker.Neutral in this.artwork || Sticker.Neutral in this.files) && this.artwork.values.all { it != 0 })
        require(this.artwork.isEmpty() || this.files.isEmpty())
    }

    @DrawableRes
    fun drawable(sticker: Sticker): Int = artwork[sticker] ?: artwork[Sticker.Neutral] ?: StickerPacks.default.drawable(sticker)

    fun file(sticker: Sticker): File? = files[sticker] ?: files[Sticker.Neutral]
}

fun InstalledStickerPack.asStickerPack() = StickerPack(id, manifest.title, manifest.companionName,
    heartColor = Color(0xFF000000L or manifest.heartColor.removePrefix("#").toLong(16)), files = images())

/** 新增内置贴纸集只需在这里注册，ID 发布后保持不变。 */
object StickerPacks {
    val default = StickerPack(
        id = DEFAULT_STICKER_PACK_ID,
        title = "小绿",
        companionName = "小绿",
        artwork = mapOf(
            Sticker.Neutral to R.drawable.midori_neutral,
            Sticker.Happy to R.drawable.midori_happy,
            Sticker.Wink to R.drawable.midori_wink,
            Sticker.Love to R.drawable.midori_love,
            Sticker.Welcome to R.drawable.midori_welcome,
            Sticker.Curious to R.drawable.midori_curious,
            Sticker.Reading to R.drawable.midori_reading,
            Sticker.Thinking to R.drawable.midori_thinking,
            Sticker.Celebrate to R.drawable.midori_celebrate,
            Sticker.Approve to R.drawable.midori_approve,
            Sticker.Sleep to R.drawable.midori_sleep,
            Sticker.Concerned to R.drawable.midori_concerned,
            Sticker.Wave to R.drawable.midori_wave,
        ),
    )
    val all: List<StickerPack> = listOf(default).also { packs ->
        require(packs.map { it.id }.distinct().size == packs.size) { "贴纸集 ID 不能重复" }
    }

    /** 旧版本或已移除的贴纸集回退默认集，保留存储 ID 以便以后恢复。 */
    fun resolve(id: String, packs: List<StickerPack> = all): StickerPack = packs.firstOrNull { it.id == id } ?: default
}

val LocalStickerPack = compositionLocalOf { StickerPacks.default }

@Composable
fun StickerIllustration(sticker: Sticker, modifier: Modifier = Modifier, pack: StickerPack = LocalStickerPack.current) {
    val file = pack.file(sticker)
    if(file == null) Image(painterResource(pack.drawable(sticker)), contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Fit)
    else {
        val context = LocalContext.current
        val request = remember(context, file) { ImageRequest.Builder(context).data(file).crossfade(false).build() }
        AsyncImage(request, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit,
            error = painterResource(pack.drawable(sticker)))
    }
}
