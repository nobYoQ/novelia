package cc.novelia.app.data.catalog

/** 原站 web/src/util/web/keyword.ts 的展示映射；顺序及每项只替换第一次出现均与原站一致。 */
internal object SiteKeywordTranslations {
    val mappings = listOf(
        "ハーレム" to "后宫", "シリアス" to "严肃", "ほのぼの" to "温暖", "バトル" to "战斗",
        "ラブコメ" to "爱情喜剧", "ハッピーエンド" to "HappyEnd", "バッドエンド" to "BadEnd",
        "嘘コク" to "假告白", "ギャグ" to "搞笑", "チート" to "作弊", "ファンタジー" to "奇幻",
        "スクールラブ" to "校园爱情", "ダーク" to "黑暗", "ミステリー" to "推理", "ヒーロー" to "主角",
        "ヒロイン" to "女主角", "ダンジョン" to "迷宫", "ざまぁ" to "活该", "ざまあ" to "活该",
        "ディストピア" to "反乌托邦", "アイドル" to "偶像", "成り上がり" to "暴发户",
        "ライトノベル" to "轻小说", "セフレ" to "性伙伴", "ホームドラマ" to "家庭剧",
        "パラレルワールド" to "平行世界", "ヤンデレ" to "病娇", "ツンデレ" to "傲娇",
        "ゲーム" to "游戏", "コミカライズ" to "漫画化", "アニメ化" to "动画化", "スキル" to "技能",
        "ボーイズラブ" to "BL", "ガールズラブ" to "GL", "いじめ" to "欺凌", "レイプ" to "强奸",
        "ロリ" to "萝莉", "コメディ" to "喜剧", "カクヨムオンリー" to "kakuyomu原创",
    )

    fun translate(original: String): String? = mappings.fold(original) { text, (jp, zh) ->
        text.replaceFirst(jp, zh)
    }.takeIf { it != original }
}
