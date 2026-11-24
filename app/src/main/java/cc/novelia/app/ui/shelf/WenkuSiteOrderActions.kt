@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.components.base.AppTextButton
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

@Composable internal fun WenkuSiteOrderActions(c: AppController, parentKey: String, detail: WenkuDetail? = null, compact: Boolean = false) {
    var busy by remember(parentKey) { mutableStateOf(false) }
    var menu by remember(parentKey) { mutableStateOf(false) }
    fun sort(descending: Boolean) {
        menu = false
        if(busy) return
        busy = true
        c.action("分卷已按网站${if(descending) "倒序" else "正序"}排列") {
            try {
                val ref = BookRef.fromKey(parentKey)
                val stored = c.store.state.value.books.firstOrNull { it.book.ref == ref }?.book?.volumeIds.orEmpty()
                val ids = detail?.siteVolumeIds().orEmpty().ifEmpty {
                    if(stored.isNotEmpty()) siteVolumeIds(stored) else c.wenkuDetail(ref).siteVolumeIds()
                }
                require(ids.isNotEmpty()) { "网站暂无可用于排序的分卷目录" }
                c.store.update { it.withWenkuSiteOrder(parentKey, ids, descending) }
            } finally { busy = false }
        }
    }
    if(compact) {
        Box {
            AppTextButton({ menu = true }, enabled = !busy) { Text(if(busy) "排序中…" else "排序") }
            AppDropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("网站正序") }, { sort(false) }, modifier = Modifier.testTag("wenku-site-order-ascending"))
                DropdownMenuItem({ Text("网站倒序") }, { sort(true) }, modifier = Modifier.testTag("wenku-site-order-descending"))
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("挂载分卷排序", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppOutlinedButton(onClick = { sort(false) }, enabled = !busy, modifier = Modifier.testTag("wenku-site-order-ascending")) { Text("网站正序") }
            AppOutlinedButton(onClick = { sort(true) }, enabled = !busy, modifier = Modifier.testTag("wenku-site-order-descending")) { Text("网站倒序") }
        }
        Text(if(busy) "正在读取网站分卷目录…" else "无法匹配的本地卷保留原顺序，排在末尾。新导入卷沿用所选方向。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
