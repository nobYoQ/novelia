package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumPage
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.PageControls

internal data class ForumReplyFocus(val rootId: Long, val page: Int, val commentId: Long)

/** 一级评论与子回复分别分页；只在展开时加载，账号或角色变化后清除旧回复内容。 */
@Composable internal fun ForumCommentThread(
    postId: Long, root: ForumComment, viewer: Profile?, version: Int,
    focus: ForumReplyFocus? = null, blockedUsers: Set<String> = emptySet(),
    loadReplies: suspend (Int) -> ForumPage<ForumComment>,
    render: @Composable (ForumComment, Boolean) -> Unit
) {
    var expanded by rememberSaveable(postId, root.id, viewer?.userId, viewer?.role) { mutableStateOf(false) }
    var page by rememberSaveable(postId, root.id, viewer?.userId, viewer?.role) { mutableIntStateOf(0) }
    var replyTotal by remember(postId, root.id, viewer?.userId, viewer?.role) { mutableStateOf<Long?>(null) }
    var handledFocus by remember(postId, root.id, viewer?.userId, viewer?.role) { mutableStateOf<Long?>(null) }
    LaunchedEffect(focus) {
        if(focus?.rootId == root.id) { expanded = true; page = focus.page }
    }
    Column(Modifier.fillMaxWidth().testTag("forum-thread-${root.id}")) {
        render(root, root.status == 0)
        // 当前线上响应可能省略计数；保留入口，不能把缺失字段当作没有回复。
        val count = replyTotal ?: root.replyCount
        TextButton(onClick = { expanded = !expanded },
            modifier = Modifier.testTag("forum-replies-toggle-${root.id}")) {
            Text(if(expanded) "收起回复" else if(count > 0) "查看 $count 条回复" else "查看回复")
        }
        if(expanded) {
            HorizontalDivider()
            AsyncContent(listOf(postId, root.id, page, viewer?.userId, viewer?.role),
                refreshKey = version, load = { loadReplies(page) }, onLoaded = { replies ->
                    replyTotal = replies.total
                    if(focus?.rootId == root.id && handledFocus != focus.commentId) {
                        handledFocus = focus.commentId
                        page = (replies.pageCount() - 1).coerceAtLeast(0)
                    }
                },
                modifier = Modifier.padding(start = 16.dp).testTag("forum-replies-${root.id}")) { replies, _ ->
                Column(Modifier.fillMaxWidth()) {
                    replies.items.filter { it.authorUsername !in blockedUsers }.forEach { reply ->
                        key(reply.id) { render(reply, root.status == 0) }
                    }
                    if(replies.items.isEmpty()) Text("暂无回复", Modifier.padding(12.dp))
                    if(replies.pageCount() > 1) PageControls(page, replies.pageCount()) { page = it }
                }
            }
        }
    }
}
