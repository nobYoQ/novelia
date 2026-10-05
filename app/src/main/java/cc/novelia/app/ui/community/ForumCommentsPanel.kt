package cc.novelia.app.ui.community

import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumCommentInput
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.markdown.MarkdownCommentInput
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.markdown.rememberMarkdownRenderer
import cc.novelia.app.ui.navigation.AppController
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The new API paginates all comments together, including replies. No parentId query exists. */
@Composable internal fun ForumCommentsPanel(c: AppController, postId: Long, locked: Boolean) {
    val profile by c.forumSession.profile.collectAsStateWithLifecycle()
    val state by c.store.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(postId) { mutableIntStateOf(0) }
    var version by remember(postId) { mutableIntStateOf(0) }
    var rootId by rememberSaveable(postId, profile?.userId) { mutableStateOf<Long?>(null) }
    var editing by remember(postId, profile?.userId) { mutableStateOf<ForumComment?>(null) }
    var deleting by remember { mutableStateOf<ForumComment?>(null) }
    var sending by remember { mutableStateOf(false) }
    val draftKey = "forum-comment:$postId:${profile?.userId ?: "guest"}:${editing?.id?.let { "edit-$it" } ?: rootId ?: "root"}"
    var text by rememberSaveable(draftKey) { mutableStateOf(state.drafts[draftKey] ?: editing?.content.orEmpty()) }
    val documentUrl = ForumLinks.articleUrl(ForumLinks.localId(postId))
    val renderer = rememberMarkdownRenderer(c, documentUrl)
    Column(Modifier.fillMaxSize()) {
        AsyncContent(listOf(postId, page, profile?.userId), refreshKey = version, load = { c.forumApi.comments(postId, page) }, modifier = Modifier.weight(1f)) { result, _ ->
            val comments = result.items.filter { it.authorUsername !in state.blockedUsers }
            AppLazyColumn(contentPadding = PaddingValues(20.dp)) {
                if(comments.isEmpty()) item { EmptyState("还没有讨论", "来分享你的感想吧。", Icons.Outlined.ChatBubbleOutline) }
                items(comments, key = { it.id }) { comment ->
                    Column(Modifier.padding(start = if(comment.rootId == null) 0.dp else 16.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${comment.authorUsername} · ${displayDate(comment.createdEpoch)}", style = MaterialTheme.typography.labelLarge)
                        comment.rootId?.let { Text("回复 #$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if(comment.status != 0) Text(if(comment.status == 2) "该评论已删除" else "该评论已隐藏")
                        else MarkdownText(c, comment.content, renderer = renderer, documentUrl = documentUrl)
                        Row {
                            if(!locked && comment.status == 0) TextButton(onClick = { editing = null; rootId = comment.replyRoot }) { Text("回复") }
                            if(comment.canModify(profile)) {
                                if(comment.status == 0) TextButton(onClick = { editing = comment; rootId = null }) { Text("编辑") }
                                TextButton(onClick = { deleting = comment }) { Text("删除") }
                            } else if(comment.authorId != profile?.userId) TextButton(onClick = { c.store.update { it.copy(blockedUsers = it.blockedUsers + comment.authorUsername) } }) { Text("屏蔽用户") }
                        }
                    }
                    HorizontalDivider()
                }
                item { PageControls(page, result.pageCount()) { page = it } }
            }
        }
        if(locked && editing == null) Text("此讨论已锁定，暂时不能回复。", Modifier.padding(20.dp))
        else Column(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            if(rootId != null || editing != null) Row {
                Text(if(editing != null) "编辑评论 #${editing?.id}" else "回复 #$rootId", Modifier.weight(1f))
                TextButton(onClick = { rootId = null; editing = null }) { Text("取消") }
            }
            MarkdownCommentInput(text, { value -> text = value; c.store.update { it.copy(drafts = it.drafts + (draftKey to value)) } }, if(editing != null) "编辑评论" else "写下评论") {
                FilledIconButton(enabled = text.isNotBlank() && text.length <= 100000 && !sending, onClick = {
                    // Login changes the account-specific draft key; retain the text being submitted.
                    val content = text.trim(); val submittedRoot = rootId; val submittedEdit = editing; val submittedDraft = draftKey
                    c.requireForumLogin { c.action {
                        sending = true
                        try {
                            if(submittedEdit != null) c.forumApi.updateComment(submittedEdit.id, content)
                            else c.forumApi.createComment(postId, ForumCommentInput(content, submittedRoot))
                            c.store.update { it.copy(drafts = it.drafts - submittedDraft) }
                            text = ""; rootId = null; editing = null; version++
                        } finally { sending = false }
                    } }
                }) { Icon(Icons.Outlined.Send, if(editing != null) "保存评论" else "发送评论") }
            }
        }
    }
    deleting?.let { comment -> ConfirmDialog("删除评论？", "评论只能在发布后 20 分钟内编辑或删除。", { deleting = null }) {
        c.action { c.forumApi.deleteComment(comment.id); deleting = null; version++ }
    } }
}
