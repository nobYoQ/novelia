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

/** 子回复正文仅展开后加载；计数由列表预读并保留，账号或角色变化后清除旧回复内容。 */
@Composable internal fun ForumCommentThread(
    postId: Long, root: ForumComment, viewer: Profile?, version: Int,
    focus: ForumReplyFocus? = null, blockedUsers: Set<String> = emptySet(),
    knownReplyCount: Long? = null, countLoading: Boolean = false, onReplyCount: (Long) -> Unit = {},
    loadReplies: suspend (Int) -> ForumPage<ForumComment>,
    render: @Composable (ForumComment, Boolean, (@Composable () -> Unit)?) -> Unit
) {
    var expanded by rememberSaveable(postId, root.id, viewer?.userId, viewer?.role) { mutableStateOf(false) }
    var page by rememberSaveable(postId, root.id, viewer?.userId, viewer?.role) { mutableIntStateOf(0) }
    var replyTotal by remember(postId, root.id, viewer?.userId, viewer?.role, version) { mutableStateOf<Long?>(null) }
    var handledFocus by rememberSaveable(postId, root.id, viewer?.userId, viewer?.role) { mutableStateOf<Long?>(null) }
    LaunchedEffect(focus) {
        if(focus?.rootId == root.id) { expanded = true; page = focus.page }
    }
    Column(Modifier.fillMaxWidth().testTag("forum-thread-${root.id}")) {
        // 当前线上响应可能省略计数；保留入口，不能把缺失字段当作没有回复。
        val count = knownReplyCount ?: replyTotal ?: root.replyCount.takeIf { it > 0 }
        render(root, root.status == 0) {
            TextButton(onClick = { expanded = !expanded }, enabled = expanded || count != 0L,
                modifier = Modifier.testTag("forum-replies-toggle-${root.id}")) {
                Text(when {
                    expanded -> "收起回复"
                    count == 0L -> "暂无回复"
                    count != null -> "查看 $count 条回复"
                    countLoading -> "正在读取回复数量…"
                    else -> "查看回复（数量暂不可用）"
                })
            }
        }
        if(expanded) {
            HorizontalDivider()
            AsyncContent(listOf(postId, root.id, page, viewer?.userId, viewer?.role),
                refreshKey = version, load = { loadReplies(page) }, onLoaded = { replies ->
                    replyTotal = replies.total
                    onReplyCount(replies.total)
                    if(focus?.rootId == root.id && handledFocus != focus.commentId) {
                        handledFocus = focus.commentId
                        page = (replies.pageCount() - 1).coerceAtLeast(0)
                    }
                },
                modifier = Modifier.padding(start = 16.dp).testTag("forum-replies-${root.id}")) { replies, _ ->
                Column(Modifier.fillMaxWidth()) {
                    replies.items.filter { it.authorUsername !in blockedUsers }.forEach { reply ->
                        key(reply.id) { render(reply, root.status == 0, null) }
                    }
                    if(replies.items.isEmpty()) Text("暂无回复", Modifier.padding(12.dp))
                    if(replies.pageCount() > 1) PageControls(page, replies.pageCount()) { page = it }
                }
            }
        }
    }
}
