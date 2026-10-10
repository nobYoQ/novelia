package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppFilledIconButton

import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumCommentInput
import cc.novelia.app.data.model.ForumRules
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.auth.Session
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.network.CommentDiscussion
import cc.novelia.app.data.network.discussion
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 一级评论分页加载并保留附带的首屏回复；展开时复用，后续页通过独立接口读取。 */
@Composable internal fun ForumCommentsPanel(c: AppController, postId: Long, locked: Boolean) {
    val api = c.forumApi
    val discussion = remember(api, postId) { api.discussion(postId) }
    DiscussionCommentsPanel(c, discussion, c.forumSession, locked,
        ForumLinks.articleUrl(ForumLinks.localId(postId)), c::requireForumLogin)
}

@Composable internal fun NovelCommentsPanel(c: AppController, site: String, locked: Boolean = false) {
    val state by c.store.state.collectAsStateWithLifecycle()
    if(state.hideNovelComments) {
        EmptyState("小说评论已隐藏", "可以在设置的阅读体验中重新开启。", Icons.Outlined.CommentsDisabled)
        return
    }
    val api = c.app.novelCommentApi
    val discussion = remember(api, site) { api.discussion(site) }
    DiscussionCommentsPanel(c, discussion, c.session, locked, MarkdownLinks.commentDocumentUrl(site), c::requireLogin)
}

@Composable private fun DiscussionCommentsPanel(c: AppController, discussion: CommentDiscussion, session: Session,
    locked: Boolean, documentUrl: String?, requireLogin: (() -> Unit) -> Unit) {
    val discussionKey = discussion.key
    val profile by session.profile.collectAsStateWithLifecycle()
    val state by c.store.state.collectAsStateWithLifecycle()
    val viewerKey = profile?.userId?.toString() ?: profile?.username
    var composerExpanded by remember(discussionKey, viewerKey) { mutableStateOf(false) }
    var page by rememberSaveable(discussionKey) { mutableIntStateOf(0) }
    var version by remember(discussionKey) { mutableIntStateOf(0) }
    // 保留在列表层，LazyColumn 回收单条评论时不会丢失计数；刷新、换账号或角色时失效。
    val binding = session.capture()
    val replyCounts = remember(discussionKey, binding, profile?.role, version) { mutableStateMapOf<Long, Long?>() }
    var countsRevision by remember(discussionKey, binding, profile?.role, version) { mutableIntStateOf(0) }
    var rootId by rememberSaveable(discussionKey, viewerKey) { mutableStateOf<Long?>(null) }
    var replyCount by rememberSaveable(discussionKey, viewerKey) { mutableLongStateOf(0) }
    var replyFocus by remember(discussionKey, binding, profile?.role) { mutableStateOf<ForumReplyFocus?>(null) }
    var editing by remember(discussionKey, viewerKey) { mutableStateOf<ForumComment?>(null) }
    var deleting by remember(discussionKey, binding) { mutableStateOf<ForumComment?>(null) }
    var sending by remember(discussionKey) { mutableStateOf(false) }
    val draftKey = "$discussionKey:${viewerKey ?: "guest"}:${editing?.id?.let { "edit-$it" } ?: rootId ?: "root"}"
    var text by rememberSaveable(draftKey) { mutableStateOf(state.drafts[draftKey] ?: editing?.content.orEmpty()) }
    val latestDraft by rememberUpdatedState(draftKey to text)
    val renderer = rememberMarkdownRenderer(c, documentUrl)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        AsyncContent(listOf(discussionKey, page, binding, profile?.role), refreshKey = version, load = {
            session.ensureCurrent(binding)
            discussion.comments(page).also { session.ensureCurrent(binding) }
        },
            onLoaded = { replyCounts.clear(); countsRevision++ }, modifier = Modifier.weight(1f)) { result, _ ->
            // 在讨论串首次组合之前初始化，避免展开时先发请求再填入首屏数据。
            val replyPages = rememberForumReplyPages(discussionKey, binding, profile?.role, version, countsRevision,
                initialRoots = result.items)
            val comments = result.items.filter { it.authorUsername !in state.blockedUsers }
            LaunchedEffect(result.items, version, countsRevision, state.blockedUsers) {
                try {
                    loadForumReplyCounts(comments.filterNot { replyCounts.containsKey(it.id) },
                        load = { session.ensureCurrent(binding); discussion.replyCount(it).also { session.ensureCurrent(binding) } }) { id, count ->
                        // 展开回复得到的更新计数优先于尚未完成的预读取。
                        if(!replyCounts.containsKey(id)) replyCounts[id] = count
                    }
                } catch(_: SessionChangedException) {
                    // 会话变化不是页面崩溃；新身份重新加载，旧批次不再继续请求。
                    comments.filterNot { replyCounts.containsKey(it.id) }.forEach { replyCounts[it.id] = null }
                }
            }
            AppLazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp,
                bottom = if(!locked && !composerExpanded) 88.dp else 20.dp)) {
                if(comments.isEmpty()) item { EmptyState("还没有讨论", "来分享你的感想吧。", Icons.Outlined.ChatBubbleOutline) }
                items(comments, key = { "${profile?.userId}:${profile?.role}:${it.id}" }) { root ->
                    ForumCommentThread(discussionKey, root, profile, version, replyFocus, state.blockedUsers,
                        knownReplyCount = replyCounts[root.id], countLoading = !replyCounts.containsKey(root.id),
                        onReplyCount = { replyCounts[root.id] = it },
                        replyPages = replyPages,
                        loadReplies = {
                            session.ensureCurrent(binding)
                            discussion.replies(root.id, it).also { session.ensureCurrent(binding) }
                        }) { comment, rootPublished, replyToggle ->
                        ForumCommentRow(comment, profile, locked || !rootPublished,
                            onReply = { editing = null; rootId = comment.replyRoot; replyCount = replyCounts[root.id] ?: root.replyCount ?: 0; composerExpanded = true },
                            onEdit = { editing = comment; rootId = null; composerExpanded = true }, onDelete = { deleting = comment },
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
        else CommentEditor(composerExpanded, { composerExpanded = false }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            val commentContent = text.trim()
            val commentError = ForumRules.contentError(commentContent, comment = true)
            val canSend = profile == null || ForumRules.canWrite(profile)
            val editNow = rememberForumModificationTime(editing?.createdEpoch ?: 0)
            val canEditDraft = editing?.canModify(profile, editNow) != false
            if(rootId != null || editing != null) Row {
                Text(if(editing != null) "编辑评论 #${editing?.id}" else "回复 #$rootId", Modifier.weight(1f))
                AppTextButton(onClick = { rootId = null; editing = null }) { Text("取消") }
            }
            MarkdownCommentInput(text, { value -> text = value; c.store.update { it.copy(drafts = it.drafts + (draftKey to value)) } }, if(editing != null) "编辑评论" else "写下评论", isError = commentContent.isNotEmpty() && commentError != null, softLimit = true, unicodeLimit = ForumRules.COMMENT_LIMIT) {
                AppFilledIconButton(enabled = commentError == null && canSend && canEditDraft && !sending, modifier = Modifier.testTag("forum-comment-submit"), onClick = {
                    // Login changes the account-specific draft key; retain the text being submitted.
                    val submittedText = text; val content = submittedText.trim(); val submittedRoot = rootId; val submittedReplyCount = replyCount; val submittedEdit = editing; val submittedDraft = draftKey
                    requireLogin { c.action {
                        require(ForumRules.canWrite(session.profile.value)) { "当前账号暂不具备评论权限，草稿已保留" }
                        require(submittedEdit == null || submittedEdit.canModify(session.profile.value)) { "评论只能在发布后 20 分钟内修改，管理员不受此限制。草稿已保留" }
                        sending = true
                        try {
                            if(submittedEdit != null) discussion.update(submittedEdit.id, content)
                            else {
                                val created = discussion.create(ForumCommentInput(content, submittedRoot))
                                submittedRoot?.let { replyFocus = ForumReplyFocus(it,
                                    (submittedReplyCount / 20).coerceIn(0, Int.MAX_VALUE - 1L).toInt(), created.id) }
                            }
                            c.store.update { if(it.drafts[submittedDraft] == submittedText) it.copy(drafts = it.drafts - submittedDraft) else it }
                            if(latestDraft == (submittedDraft to submittedText)) { text = ""; rootId = null; editing = null; composerExpanded = false }
                            version++
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
    }
    CommentButton((!locked || editing != null) && !composerExpanded, { composerExpanded = true },
        Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
    deleting?.let { comment -> ConfirmDialog("删除评论？", "确定删除这条评论吗？", { deleting = null }, confirmLabel = "删除评论") {
        c.action {
            require(comment.canModify(session.profile.value)) { "评论只能在发布后 20 分钟内删除，管理员不受此限制" }
            discussion.delete(comment.id); deleting = null; version++
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
            if(!locked && comment.status == 0) AppTextButton(onClick = onReply) { Text("回复") }
            if(comment.status == 0 && comment.canModify(profile, now)) {
                if(ForumRules.canWrite(profile)) AppTextButton(onClick = onEdit) { Text("编辑") }
                AppTextButton(onClick = onDelete) { Text("删除") }
            } else if(comment.authorId != profile?.userId) AppTextButton(onClick = onBlock) { Text("屏蔽用户") }
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
            AppTextButton(onClick = { expanded = !expanded }) { Text("$label · ${if(expanded) "收起原文" else "查看原文"}") }
            if(expanded) render(comment.content)
        } else Text(label)
    }
}
