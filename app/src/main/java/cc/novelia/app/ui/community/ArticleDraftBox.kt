package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppFilledTonalButton
import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.network.encodeSegment
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.MenuRow
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun ArticleDraftBox(c: AppController, onClose: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val drafts = remember(state.drafts) { ArticleDrafts.posts(state.drafts) }
    var deleting by remember { mutableStateOf<ArticleDraft?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("帖子草稿箱", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        AppTextButton(onClick = onClose) { Text("关闭") }
    }
    Text("新帖和修改草稿保存在此设备。旧站草稿按新帖续写，发布前需重新选择分类。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
    AppFilledTonalButton(onClick = { onClose(); c.go("compose") }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(" 新建草稿")
    }
    AppLazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        if(drafts.isEmpty()) item { EmptyState("还没有帖子草稿", "新建帖子或修改已发布的帖子，输入会自动保存。", Icons.Outlined.Drafts) }
        items(drafts, key = { it.key }) { draft ->
            val articleId = ArticleDrafts.editedArticleId(draft.key)
            val source = if(ArticleDrafts.isForumNewPostKey(draft.key) || articleId != null) "论坛" else "恢复的旧草稿"
            val kind = if(articleId == null) "新帖" else "修改已发布帖子"
            val length = if(draft.content.isBlank()) "仅标题" else "${draft.content.length} 字"
            MenuRow(draft.displayTitle, "$source · $kind · $length · 点击续写", Icons.Outlined.Description,
                { onClose(); c.go(if(articleId == null) "compose?draft=${encodeSegment(draft.key)}" else "compose?article=${encodeSegment(articleId)}") },
                trailing = { AppIconButton(onClick = { deleting = draft }) { Icon(Icons.Outlined.DeleteOutline, "删除草稿 ${draft.displayTitle}") } })
        }
    }
    deleting?.let { draft ->
        AppAlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除草稿？") },
            text = { Text("“${draft.displayTitle}”的本地草稿将被删除。") },
            confirmButton = { AppTextButton(onClick = { c.store.update { it.copy(drafts = it.drafts - draft.key) }; deleting = null }) { Text("删除草稿") } },
            dismissButton = { AppTextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
