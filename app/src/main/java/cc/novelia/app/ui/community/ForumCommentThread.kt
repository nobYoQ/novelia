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
import cc.novelia.app.data.network.ForumReplyPageCache
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.PageControls

internal data class ForumReplyFocus(val rootId: Long, val page: Int, val commentId: Long)

@Composable internal fun rememberForumReplyPages(vararg keys: Any?, initialRoots: List<ForumComment> = emptyList()): ForumReplyPageCache {
    val scope = rememberCoroutineScope()
    val pages = remember(*keys) { ForumReplyPageCache(scope).apply { initialRoots.forEach { seedFirstPage(it) } } }
    DisposableEffect(pages) { onDispose { pages.close() } }
    return pages
}

/** 子回复展开时复用首屏；discussionKey 区分帖子与小说，防止相同评论 ID 串用状态。 */
@Composable internal fun ForumCommentThread(
    discussionKey: Any, root: ForumComment, viewer: Profile?, version: Int,
    focus: ForumReplyFocus? = null, blockedUsers: Set<String> = emptySet(),
    knownReplyCount: Long? = null, countLoading: Boolean = false, onReplyCount: (Long) -> Unit = {},
    replyPages: ForumReplyPageCache = rememberForumReplyPages(discussionKey, root.id, viewer?.userId, viewer?.role, version, initialRoots = listOf(root)),
    loadReplies: suspend (Int) -> ForumPage<ForumComment>,
    render: @Composable (ForumComment, Boolean, (@Composable () -> Unit)?) -> Unit
) {
    var expanded by rememberSaveable(discussionKey, root.id, viewer?.userId, viewer?.role) { mutableStateOf(false) }
    var page by rememberSaveable(discussionKey, root.id, viewer?.userId, viewer?.role) { mutableIntStateOf(0) }
    var replyTotal by remember(discussionKey, root.id, viewer?.userId, viewer?.role, version) { mutableStateOf<Long?>(null) }
    var handledFocus by rememberSaveable(discussionKey, root.id, viewer?.userId, viewer?.role) { mutableStateOf<Long?>(null) }
    LaunchedEffect(focus) {
        if(focus?.rootId == root.id && handledFocus != focus.commentId) { expanded = true; page = focus.page }
    }
    val onPageLoaded: (ForumPage<ForumComment>) -> Unit = { replies ->
        replyTotal = replies.total
        onReplyCount(replies.total)
        val lastPage = (replies.pageCount() - 1).coerceAtLeast(0)
        if(focus?.rootId == root.id && handledFocus != focus.commentId) {
            handledFocus = focus.commentId
            page = lastPage
        } else page = page.coerceAtMost(lastPage)
    }
    LaunchedEffect(replyPages, page, expanded) {
        // 使用缓存种子时 AsyncContent 跳过加载，也要恢复计数与有效页码。
        replyPages.peek(root.id, page)?.let(onPageLoaded)
    }
    Column(Modifier.fillMaxWidth().testTag("forum-thread-${root.id}")) {
        // 回复页的更新计数优先；服务端的 0 表示无回复，只有缺失字段才保留未知入口。
        val count = knownReplyCount ?: replyPages.peek(root.id, page)?.total ?: replyTotal ?: root.replyCount?.takeIf { it >= 0 }
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
            AsyncContent(listOf(discussionKey, root.id, page, viewer?.userId, viewer?.role, replyPages),
                initialResult = replyPages.peek(root.id, page),
                refreshKey = version, load = { replyPages.load(root.id, page) { loadReplies(page) } }, onLoaded = onPageLoaded,
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
