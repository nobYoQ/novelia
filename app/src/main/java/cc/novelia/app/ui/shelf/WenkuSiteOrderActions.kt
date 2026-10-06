@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.siteVolumeIds
import cc.novelia.app.data.library.withWenkuSiteOrder
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun WenkuSiteOrderActions(c: AppController, parentKey: String) {
    var busy by remember(parentKey) { mutableStateOf(false) }
    fun sort(descending: Boolean) {
        if(busy) return
        busy = true
        c.action("分卷已按网站${if(descending) "倒序" else "正序"}排列") {
            try {
                val ref = BookRef.fromKey(parentKey)
                val detail = c.detail<WenkuDetail>("wenku/${ref.id}", forceNetwork = true)
                val ids = detail.siteVolumeIds()
                require(ids.isNotEmpty()) { "网站暂无可用于排序的分卷目录" }
                c.store.update { it.withWenkuSiteOrder(parentKey, ids, descending) }
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("挂载分卷排序", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { sort(false) }, enabled = !busy, modifier = Modifier.testTag("wenku-site-order-ascending")) { Text("网站正序") }
            OutlinedButton(onClick = { sort(true) }, enabled = !busy, modifier = Modifier.testTag("wenku-site-order-descending")) { Text("网站倒序") }
        }
        Text(if(busy) "正在读取网站分卷目录…" else "无法匹配的本地卷保留原顺序，排在末尾。新导入卷沿用所选方向。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
