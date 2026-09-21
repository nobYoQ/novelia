@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.community

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.markdown.MarkdownLinks
import cc.novelia.app.data.model.Comment
import cc.novelia.app.data.model.Page
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.markdown.MarkdownCommentInput
import cc.novelia.app.ui.markdown.MarkdownText
import cc.novelia.app.ui.markdown.rememberMarkdownRenderer
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.launch

@Composable fun CommentsPanel(c: AppController, site: String, locked: Boolean = false, parent: String? = null) {
    var page by rememberSaveable(site, parent) { mutableIntStateOf(0) }; var version by remember { mutableIntStateOf(0) }; var text by rememberSaveable(site, parent) { mutableStateOf(c.store.state.value.drafts["comment:$site:$parent"].orEmpty()) }; var reply by remember { mutableStateOf<Comment?>(null) }; var sending by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf<Comment?>(null) }
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val preferences by c.store.state.collectAsStateWithLifecycle()
    val reducedMotion = appReducedMotion()
    val feedback = remember(site, parent) { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    if(!site.startsWith("article-") && preferences.hideNovelComments) { EmptyState("小说评论已隐藏", "可以在阅读与外观设置中重新开启。", Icons.Outlined.CommentsDisabled); return }
    val markdownRenderer = rememberMarkdownRenderer(c)
    val documentUrl = remember(site) { MarkdownLinks.commentDocumentUrl(site) }
    Column(Modifier.fillMaxSize()) {
        AsyncContent(listOf(site, parent, page, profile?.username), refreshKey = version, load = { c.api.get<Page<Comment>>("comment", buildMap { put("site", site); put("page", "$page"); put("pageSize", "20"); parent?.let { put("parentId", it) } }) }, modifier = Modifier.weight(1f)) { result, _ ->
            val comments = remember(result.items, preferences.blockedUsers) { result.items.filter { it.user.username !in preferences.blockedUsers } }
            AppLazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                if(comments.isEmpty()) item {
                    if(result.items.isNotEmpty()) EmptyState("本页评论已屏蔽", "可撤销刚才的屏蔽，或在屏蔽管理中恢复用户。", Icons.Outlined.PersonOff)
                    else EmptyState("还没有讨论", "读完之后，来分享你的感想吧。", Icons.Outlined.ChatBubbleOutline)
                }
                items(comments, key = { it.id }, contentType = { "comment" }) { comment ->
                    Column(if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Quick), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))) {
                        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(comment.user.username, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(displayDate(comment.createAt), style = MaterialTheme.typography.labelSmall) }
                            if(comment.hidden) Text("这条评论已被隐藏", style = MaterialTheme.typography.bodyMedium) else MarkdownText(c, comment.content, renderer = markdownRenderer, documentUrl = documentUrl)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if(parent == null) TextButton(onClick = { reply = comment }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if(comment.numReplies > 0) "${comment.numReplies} 条回复 · 查看/回复" else if(locked) "查看回复" else "回复") }
                                Spacer(Modifier.weight(1f))
                                CommentMoreMenu(comment.user.username, comment.user.username == profile?.username,
                                    onDelete = { deleting = comment }, onBlock = {
                                        scope.launch { blockCommentUser(c, feedback, comment.user.username) }
                                    })
                            }
                            val replies = remember(comment.replies, preferences.blockedUsers) { comment.replies.asSequence().filter { it.user.username !in preferences.blockedUsers }.take(2).toList() }
                            replies.forEach { child -> key(child.id) { Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(child.user.username, style = MaterialTheme.typography.labelMedium); if(child.hidden) Text("评论已隐藏", style = MaterialTheme.typography.bodyMedium) else MarkdownText(c, child.content, renderer = markdownRenderer, documentUrl = documentUrl) } } } }
                        }
                        HorizontalDivider()
                    }
                }
                item { PageControls(page, result.pageNumber) { page = it } }
            }
        }
        SnackbarHost(feedback, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if(locked) Text("此讨论已锁定，暂时不能回复。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
        else Box(Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            MarkdownCommentInput(text, { text = it; c.store.update { s -> s.copy(drafts = s.drafts + ("comment:$site:$parent" to text)) } }, if(parent == null) "写下评论" else "回复这条评论") {
            FilledIconButton(onClick = { c.requireLogin { c.action { sending = true; try { val submittedText = text; val body = buildMap { put("site", site); put("content", submittedText.trim()); parent?.let { put("parent", it) } }; c.api.post("comment", body); if(text == submittedText) { text = ""; c.store.update { it.copy(drafts = it.drafts - "comment:$site:$parent") } }; version++ } finally { sending = false } } } }, enabled = text.isNotBlank() && !sending) { Icon(Icons.Outlined.Send, "发送评论") }
            }
        }
    }
    reply?.let { comment -> AppSheet(onDismissRequest = { reply = null }) { Column(Modifier.fillMaxHeight(.85f)) { Text("回复 ${comment.user.username}", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge); CommentsPanel(c, site, locked, comment.id) } } }
    deleting?.let { comment -> ConfirmDialog("删除评论？", "这条评论将从原站移除。", { deleting = null }, confirmLabel = "删除评论") { c.action { c.api.request("DELETE", "comment/${comment.id}"); version++ } } }
}

internal suspend fun blockCommentUser(c: AppController, feedback: SnackbarHostState, username: String) {
    c.store.update { it.copy(blockedUsers = it.blockedUsers + username) }
    if(feedback.showSnackbar("已屏蔽 $username", actionLabel = "撤销", withDismissAction = true,
            duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
        c.store.update { it.copy(blockedUsers = it.blockedUsers - username) }
    }
}

@Composable internal fun CommentMoreMenu(username: String, ownComment: Boolean, onDelete: () -> Unit, onBlock: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreVert, "$username 的评论更多操作") }
        AppDropdownMenu(expanded, { expanded = false }) {
            if(ownComment) DropdownMenuItem(text = { Text("删除评论") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                modifier = Modifier.heightIn(min = 48.dp), onClick = { expanded = false; onDelete() })
            else DropdownMenuItem(text = { Text("屏蔽用户") }, leadingIcon = { Icon(Icons.Outlined.PersonOff, null) },
                modifier = Modifier.heightIn(min = 48.dp), onClick = { expanded = false; onBlock() })
        }
    }
}
