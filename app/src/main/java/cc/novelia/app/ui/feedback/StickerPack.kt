package cc.novelia.app.ui.feedback

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import cc.novelia.app.R
import cc.novelia.app.data.model.DEFAULT_STICKER_PACK_ID

/** 页面只表达用途，具体角色和素材由当前贴纸集提供。 */
enum class Sticker(val description: String) {
    Neutral("等你打招呼"),
    Happy("开心地笑了"),
    Wink("向你眨眨眼"),
    Love("收到你的喜欢啦"),
    Welcome("欢迎新的故事"),
    Curious("正在寻找故事"),
    Reading("安静阅读"),
    Thinking("若有所思"),
    Celebrate("开心庆祝"),
    Approve("完成啦"),
    Sleep("安心休息"),
    Concerned("遇到一点小状况"),
    Wave("向你招手"),
}

/**
 * 内置贴纸集。必须提供常态图，缺少其他表情时使用本集常态图，避免混入另一角色。
 * 复制素材映射，防止注册后被外部修改；图片保留透明边缘和原始宽高比。
 */
class StickerPack(
    val id: String,
    val title: String,
    val companionName: String,
    artwork: Map<Sticker, Int>,
    val heartColor: Color = Color(0xFFE15C86),
) {
    private val artwork = artwork.toMap()

    init {
        require(id.isNotBlank() && title.isNotBlank() && companionName.isNotBlank())
        require(Sticker.Neutral in this.artwork && this.artwork.values.all { it != 0 })
    }

    @DrawableRes
    fun drawable(sticker: Sticker): Int = artwork[sticker] ?: artwork.getValue(Sticker.Neutral)
}

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
    fun resolve(id: String): StickerPack = all.firstOrNull { it.id == id } ?: default
}

val LocalStickerPack = compositionLocalOf { StickerPacks.default }

@Composable
fun StickerIllustration(sticker: Sticker, modifier: Modifier = Modifier, pack: StickerPack = LocalStickerPack.current) {
    Image(painterResource(pack.drawable(sticker)), contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Fit)
}
