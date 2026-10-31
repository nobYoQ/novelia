@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.ui.components.base.MenuRow
import kotlinx.coroutines.CancellationException

@Composable internal fun RelatedWebNovelsSheet(
    refs: List<BookRef>,
    requestKey: Any,
    loadTitle: suspend (BookRef) -> String,
    onOpen: (BookRef) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentLoadTitle by rememberUpdatedState(loadTitle)
    AppSheet(onDismissRequest = onDismiss) {
        Text("相关网络小说", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        AppLazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(refs, key = { it.key }) { ref ->
                // 书名只是辅助信息，加载中或失败时仍可通过关联 ID 打开作品。
                val title by produceState<Result<String>?>(null, requestKey, ref) {
                    value = null
                    value = try {
                        Result.success(currentLoadTitle(ref))
                    } catch(e: CancellationException) {
                        throw e
                    } catch(e: Exception) {
                        Result.failure(e)
                    }
                }
                val name = title?.getOrNull()?.takeIf(String::isNotBlank)
                val status = when {
                    title == null -> "正在加载书名…"
                    name == null -> "书名暂不可用，点击查看详情"
                    else -> ref.id
                }
                MenuRow(
                    title = name ?: ref.key,
                    description = "${providers[ref.provider] ?: ref.provider} · $status",
                    icon = Icons.Outlined.Language,
                    onClick = { onOpen(ref) },
                    trailing = { Icon(Icons.Outlined.ChevronRight, null) },
                )
            }
        }
    }
}
