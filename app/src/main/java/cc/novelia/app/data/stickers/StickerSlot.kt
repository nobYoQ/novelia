package cc.novelia.app.data.stickers

/** key 是贴纸包格式的一部分，发布后不随界面名称改变。 */
enum class StickerSlot(val key: String, val title: String, val description: String) {
    Neutral("neutral", "常态", "等你打招呼"),
    Happy("happy", "开心", "开心地笑了"),
    Wink("wink", "眨眼", "向你眨眨眼"),
    Love("love", "喜欢", "收到你的喜欢啦"),
    Welcome("welcome", "欢迎", "欢迎新的故事"),
    Curious("curious", "好奇", "正在寻找故事"),
    Reading("reading", "阅读", "安静阅读"),
    Thinking("thinking", "思考", "若有所思"),
    Celebrate("celebrate", "庆祝", "开心庆祝"),
    Approve("approve", "完成", "完成啦"),
    Sleep("sleep", "休息", "安心休息"),
    Concerned("concerned", "担心", "遇到一点小状况"),
    Wave("wave", "招手", "向你招手"),
}

/** 先匹配文件名，再将其余图片依次填入空位，绝不静默忽略多余图片。 */
fun assignStickerImages(names: List<String>, occupied: Set<StickerSlot>): List<StickerSlot> {
    require(names.size in 1..StickerSlot.entries.size) { "一次请选择 1–13 张图片" }
    val assigned = names.map { name ->
        val stem = name.substringAfterLast('/').substringBeforeLast('.').lowercase()
        StickerSlot.entries.firstOrNull { it.key == stem || it.title == stem }
    }.toMutableList()
    val known = assigned.filterNotNull()
    require(known.distinct().size == known.size) { "多张图片对应同一表情，请分次选择" }
    val available = StickerSlot.entries.filter { it !in known && it !in occupied }.iterator()
    assigned.indices.forEach { index ->
        if(assigned[index] == null) {
            require(available.hasNext()) { "图片数量超过空余表情，请逐个选择要替换的图片" }
            assigned[index] = available.next()
        }
    }
    return assigned.map { requireNotNull(it) }
}
