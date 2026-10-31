package cc.novelia.app.reader

import cc.novelia.app.data.model.InlineKind
import cc.novelia.app.data.model.ReaderSettings

/** 渲染时过滤附加显示，正文投影与搜索/笔记锚点不随开关变化。 */
internal fun TextPart.withInlinePreferences(settings: ReaderSettings): TextPart = copy(inline = inline.filter {
    when (it.kind) {
        InlineKind.Ruby -> settings.showEpubRuby
        InlineKind.Note -> settings.epubFootnotePopup
        else -> settings.showEpubStyles
    }
})
