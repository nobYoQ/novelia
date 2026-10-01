package cc.novelia.app.ui.community

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
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun ArticleDraftBox(c: AppController, onClose: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val drafts = remember(state.drafts) { ArticleDrafts.newPosts(state.drafts) }
    var deleting by remember { mutableStateOf<ArticleDraft?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("新帖草稿箱", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onClose) { Text("关闭") }
    }
    Text("草稿保存在此设备，可离线继续写作。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
    FilledTonalButton(onClick = { onClose(); c.go("compose") }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(" 新建草稿")
    }
    AppLazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        if(drafts.isEmpty()) item { EmptyState("还没有新帖草稿", "新建一篇帖子，输入会自动保存。", Icons.Outlined.Drafts) }
        items(drafts, key = { it.key }) { draft ->
            MenuRow(draft.displayTitle, "${categories[draft.category]} · ${draft.content.length} 字 · 点击续写", Icons.Outlined.Description,
                { onClose(); c.go("compose?draft=${encodeSegment(draft.key)}") },
                trailing = { IconButton(onClick = { deleting = draft }) { Icon(Icons.Outlined.DeleteOutline, "删除草稿 ${draft.displayTitle}") } })
        }
    }
    deleting?.let { draft ->
        AppAlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除草稿？") },
            text = { Text("“${draft.displayTitle}”的本地草稿将被删除。") },
            confirmButton = { TextButton(onClick = { c.store.update { it.copy(drafts = it.drafts - draft.key) }; deleting = null }) { Text("删除草稿") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
