package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.community.ForumLinks

/** 新帖发布提示独立于可关闭的首次社区守则提醒。 */
@Composable internal fun ForumPublishingNotice(onOpen: (String) -> Unit) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val notice = buildAnnotatedString {
        fun link(label: String, path: String) {
            withLink(LinkAnnotation.Clickable(path, linkStyle) { onOpen(ForumLinks.ORIGIN + path) }) { append(label) }
        }
        append("• 提问前先阅读")
        link("站务公告", "/c/announcements")
        append("中的新人教程，再搜索")
        link("意见反馈", "/c/feedback")
        append("中的相同案例，避免重复发帖。\n\n")
        append("• 报错请详述问题，并附小说链接或截图。\n\n")
        append("• 求书请前往")
        link("求书集中帖", "/c/novel?q=%E6%B1%82%E4%B9%A6%E9%9B%86%E4%B8%AD%E8%B4%B4")
        append("，请勿单独发帖。\n\n")
        append("• 碰到此类建政小说时，不要在论坛发帖或群里讨论，请通过 QQ 或 Telegram 私信管理员处理。\n\n")
        append("• 发言请遵守")
        link("社区守则", "/rules")
        append("，累计三次违规即出局。")
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("forum-publishing-notice")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发帖前请确认", style = MaterialTheme.typography.titleSmall)
            Text(notice, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
