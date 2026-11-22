package cc.novelia.app.ui.components.base

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Image
import cc.novelia.app.sharing.ShareCard
import cc.novelia.app.sharing.ShareCardDialog
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 小说和帖子共用的分享入口；标题来自当前展示的详情，不额外请求网络。 */
@Composable internal fun ContentShareButton(
    url: String?,
    card: ShareCard?,
    contentDescription: String,
    onShare: (String) -> Unit,
) {
    var expanded by remember(url) { mutableStateOf(false) }
    var preview by remember(card) { mutableStateOf(false) }
    val name = card?.title?.trim()?.takeIf(String::isNotBlank)
    Box {
        AppIconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.Share, contentDescription) }
        AppDropdownMenu(expanded, { expanded = false }, Modifier.widthIn(max = 320.dp)) {
            if (url != null) DropdownMenuItem(
                text = { Text("仅链接") },
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                onClick = { expanded = false; onShare(url) },
            )
            if (url != null) DropdownMenuItem(
                text = {
                    Column {
                        Text("标题与链接")
                        Text(name ?: "标题暂不可用，请先加载详情", style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                },
                leadingIcon = { Icon(Icons.Outlined.Title, null) },
                enabled = name != null,
                onClick = { name?.let { expanded = false; onShare("$it\n$url") } },
            )
            DropdownMenuItem(text = { Text("生成分享图") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                enabled = card != null, onClick = { expanded = false; preview = true })
        }
    }
    if (preview && card != null) ShareCardDialog(card) { preview = false }
}
