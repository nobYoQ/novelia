package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.cache.DetailUiKey
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.CachedAsyncContent
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun WenkuDetailContent(
    c: AppController, key: DetailUiKey, version: Int, saved: Boolean, modifier: Modifier,
    content: @Composable (WenkuDetail?) -> Unit,
) {
    val latest by rememberUpdatedState(content)
    // 本机先显示。原站资料到达时移动同一份详情组合，保留已打开的挂载管理和分卷滚动位置。
    val page = remember(key) { movableContentOf<WenkuDetail?> { detail -> latest(detail) } }
    CachedAsyncContent(c.uiResults.wenkuDetails, key, refreshKey = version, modifier = modifier,
        load = { c.detail<WenkuDetail>(key.path, forceNetwork = version > 0 || c.uiResults.wenkuDetails.snapshot(key) != null) },
        validate = { c.session.ensureCurrent(key.binding) }, revealContent = true,
        unavailableContent = if(!saved) null else { loading, retry ->
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if(loading) "正在加载原站资料…" else "原站资料暂不可用，已显示本机书架资料",
                        Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    if(!loading) AppTextButton(retry) { Text("重试") }
                }
                page(null)
            }
        }) { detail, _ -> page(detail) }
}
