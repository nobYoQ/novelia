package cc.novelia.app.ui.book

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.components.media.MetaParagraph

@Composable internal fun WenkuPublicationInfo(detail: WenkuDetail, onSearchName: (String) -> Unit) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    MetaParagraph("出版信息", buildAnnotatedString {
        fun names(label: String, values: List<String>) {
            val names = values.map(String::trim).filter(String::isNotBlank)
            if(names.isEmpty()) return
            if(length > 0) append('\n')
            append("$label：")
            names.forEachIndexed { index, name ->
                if(index > 0) append(", ")
                withLink(LinkAnnotation.Clickable("$label-$index", linkStyle) { onSearchName(name) }) { append(name) }
            }
        }
        names("作者", detail.authors)
        names("插画", detail.artists)
        listOfNotNull(detail.publisher, detail.imprint).forEach { line ->
            if(length > 0) append('\n')
            append(line)
        }
    })
}
