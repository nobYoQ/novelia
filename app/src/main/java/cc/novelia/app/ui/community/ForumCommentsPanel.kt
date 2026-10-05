package cc.novelia.app.ui.community

import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumCommentInput
import cc.novelia.app.data.model.ForumRules
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.network.loadForumReplyCounts
import cc.novelia.app.data.auth.SessionChangedException
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 一级评论分页加载并保留附带的首屏回复；展开时复用，后续页通过独立接口读取。 */
@Composable internal fun ForumCommentsPanel(c: AppController, postId: Long, locked: Boolean) {
    val profile by c.forumSession.profile.collectAsStateWithLifecycle()
    val state by c.store.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(postId) { mutableIntStateOf(0) }
    var version by remember(postId) { mutableIntStateOf(0) }
    // 保留在列表层，LazyColumn 回收单条评论时不会丢失计数；刷新、换账号或角色时失效。
    val replyCounts = remember(postId, profile?.userId, profile?.role, version) { mutableStateMapOf<Long, Long?>() }
    var countsRevision by remember(postId, profile?.userId, profile?.role, version) { mutableIntStateOf(0) }
    val binding = c.forumSession.capture()
    var rootId by rememberSaveable(postId, profile?.userId) { mutableStateOf<Long?>(null) }
    var replyCount by rememberSaveable(postId, profile?.userId) { mutableLongStateOf(0) }
    var replyFocus by remember(postId, profile?.userId, profile?.role) { mutableStateOf<ForumReplyFocus?>(null) }
    var editing by remember(postId, profile?.userId) { mutableStateOf<ForumComment?>(null) }
    var deleting by remember { mutableStateOf<ForumComment?>(null) }
    var sending by remember { mutableStateOf(false) }
    val draftKey = "forum-comment:$postId:${profile?.userId ?: "guest"}:${editing?.id?.let { "edit-$it" } ?: rootId ?: "root"}"
    var text by rememberSaveable(draftKey) { mutableStateOf(state.drafts[draftKey] ?: editing?.content.orEmpty()) }
    val documentUrl = ForumLinks.articleUrl(ForumLinks.localId(postId))
    val renderer = rememberMarkdownRenderer(c, documentUrl)
    Column(Modifier.fillMaxSize()) {
        AsyncContent(listOf(postId, page, binding, profile?.userId, profile?.role), refreshKey = version, load = {
            c.forumSession.ensureCurrent(binding)
            c.forumApi.comments(postId, page).also { c.forumSession.ensureCurrent(binding) }
        },
            onLoaded = { replyCounts.clear(); countsRevision++ }, modifier = Modifier.weight(1f)) { result, _ ->
            // 在讨论串首次组合之前初始化，避免展开时先发请求再填入首屏数据。
            val replyPages = rememberForumReplyPages(postId, binding, profile?.userId, profile?.role, version, countsRevision,
                initialRoots = result.items)
            val comments = result.items.filter { it.authorUsername !in state.blockedUsers }
            LaunchedEffect(result.items, version, countsRevision, state.blockedUsers) {
                try {
                    loadForumReplyCounts(comments.filterNot { replyCounts.containsKey(it.id) },
                        load = { c.forumApi.replyCount(postId, it) }) { id, count ->
                        // 展开回复得到的更新计数优先于尚未完成的预读取。
                        if(!replyCounts.containsKey(id)) replyCounts[id] = count
                    }
                } catch(_: SessionChangedException) {
                    // 会话变化不是页面崩溃；新身份重新加载，旧批次不再继续请求。
                    comments.filterNot { replyCounts.containsKey(it.id) }.forEach { replyCounts[it.id] = null }
                }
            }
            AppLazyColumn(contentPadding = PaddingValues(20.dp)) {
                if(comments.isEmpty()) item { EmptyState("还没有讨论", "来分享你的感想吧。", Icons.Outlined.ChatBubbleOutline) }
                items(comments, key = { "${profile?.userId}:${profile?.role}:${it.id}" }) { root ->
                    ForumCommentThread(postId, root, profile, version, replyFocus, state.blockedUsers,
                        knownReplyCount = replyCounts[root.id], countLoading = !replyCounts.containsKey(root.id),
                        onReplyCount = { replyCounts[root.id] = it },
                        replyPages = replyPages,
                        loadReplies = {
                            c.forumSession.ensureCurrent(binding)
                            c.forumApi.replies(postId, root.id, it).also { c.forumSession.ensureCurrent(binding) }
                        }) { comment, rootPublished, replyToggle ->
                        ForumCommentRow(comment, profile, locked || !rootPublished,
                            onReply = { editing = null; rootId = comment.replyRoot; replyCount = replyCounts[root.id] ?: root.replyCount ?: 0 },
                            onEdit = { editing = comment; rootId = null }, onDelete = { deleting = comment },
                            onBlock = { c.store.update { it.copy(blockedUsers = it.blockedUsers + comment.authorUsername) } }, extraAction = replyToggle) {
                            MarkdownText(c, it, renderer = renderer, documentUrl = documentUrl)
                        }
                    }
                    HorizontalDivider()
                }
                item { PageControls(page, result.pageCount()) { page = it } }
            }
        }
        if(locked && editing == null) Text("此讨论已锁定，暂时不能回复。", Modifier.padding(20.dp))
        else Column(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            val commentContent = text.trim()
            val commentError = ForumRules.contentError(commentContent, comment = true)
            val canSend = profile == null || ForumRules.canWrite(profile)
            val editNow = rememberForumModificationTime(editing?.createdEpoch ?: 0)
            val canEditDraft = editing?.canModify(profile, editNow) != false
            if(rootId != null || editing != null) Row {
                Text(if(editing != null) "编辑评论 #${editing?.id}" else "回复 #$rootId", Modifier.weight(1f))
                TextButton(onClick = { rootId = null; editing = null }) { Text("取消") }
            }
            MarkdownCommentInput(text, { value -> text = value; c.store.update { it.copy(drafts = it.drafts + (draftKey to value)) } }, if(editing != null) "编辑评论" else "写下评论", isError = commentContent.isNotEmpty() && commentError != null, softLimit = true, unicodeLimit = ForumRules.COMMENT_LIMIT) {
                FilledIconButton(enabled = commentError == null && canSend && canEditDraft && !sending, modifier = Modifier.testTag("forum-comment-submit"), onClick = {
                    // Login changes the account-specific draft key; retain the text being submitted.
                    val content = text.trim(); val submittedRoot = rootId; val submittedReplyCount = replyCount; val submittedEdit = editing; val submittedDraft = draftKey
                    c.requireForumLogin { c.action {
                        require(ForumRules.canWrite(c.forumSession.profile.value)) { "当前账号暂不具备评论权限，草稿已保留" }
                        require(submittedEdit == null || submittedEdit.canModify(c.forumSession.profile.value)) { "评论只能在发布后 20 分钟内修改，管理员不受此限制。草稿已保留" }
                        sending = true
                        try {
                            if(submittedEdit != null) c.forumApi.updateComment(submittedEdit.id, content)
                            else {
                                val created = c.forumApi.createComment(postId, ForumCommentInput(content, submittedRoot))
                                submittedRoot?.let { replyFocus = ForumReplyFocus(it,
                                    (submittedReplyCount / 20).coerceIn(0, Int.MAX_VALUE - 1L).toInt(), created.id) }
                            }
                            c.store.update { it.copy(drafts = it.drafts - submittedDraft) }
                            text = ""; rootId = null; editing = null; version++
                        } finally { sending = false }
                    } }
                }) { Icon(Icons.Outlined.Send, if(editing != null) "保存评论" else "发送评论") }
            }
            Text("${ForumRules.length(commentContent)} / ${ForumRules.COMMENT_LIMIT}", style = MaterialTheme.typography.bodySmall,
                color = if(commentError != null && commentContent.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if(commentError != null && commentContent.isNotEmpty()) Text(commentError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if(!canSend) Text("当前账号暂不具备评论权限，草稿会保存在此设备。", style = MaterialTheme.typography.bodySmall)
            if(!canEditDraft) Text("评论修改时限已过，草稿已保留。", style = MaterialTheme.typography.bodySmall)
        }
    }
    deleting?.let { comment -> ConfirmDialog("删除评论？", "确定删除这条评论吗？", { deleting = null }, confirmLabel = "删除评论") {
        c.action {
            require(comment.canModify(c.forumSession.profile.value)) { "评论只能在发布后 20 分钟内删除，管理员不受此限制" }
            c.forumApi.deleteComment(comment.id); deleting = null; version++
        }
    } }
}

@Composable internal fun ForumCommentRow(comment: ForumComment, profile: Profile?, locked: Boolean,
    onReply: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onBlock: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    render: @Composable (String) -> Unit) {
    val now = rememberForumModificationTime(comment.createdEpoch)
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${comment.authorUsername} · ${displayDate(comment.createdEpoch)}", style = MaterialTheme.typography.labelLarge)
        comment.rootId?.let { Text("回复 #$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ForumCommentContent(comment, profile, render)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if(!locked && comment.status == 0) TextButton(onClick = onReply) { Text("回复") }
            if(comment.status == 0 && comment.canModify(profile, now)) {
                if(ForumRules.canWrite(profile)) TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            } else if(comment.authorId != profile?.userId) TextButton(onClick = onBlock) { Text("屏蔽用户") }
            extraAction?.invoke()
        }
    }
}

@Composable internal fun ForumCommentContent(comment: ForumComment, profile: Profile?, render: @Composable (String) -> Unit) {
    var expanded by remember(comment.id, comment.status, profile?.userId, profile?.role) { mutableStateOf(false) }
    if(comment.status == 0) render(comment.content)
    else {
        val label = if(comment.status == 2) "该评论已删除" else "该评论已隐藏"
        if(profile?.role == "admin" && comment.content.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) { Text("$label · ${if(expanded) "收起原文" else "查看原文"}") }
            if(expanded) render(comment.content)
        } else Text(label)
    }
}
