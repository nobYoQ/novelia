@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ComicPreferences
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.reader.ReaderPreferenceSection

@Composable internal fun ComicDisplaySettings(prefs: ComicPreferences, default: ComicReadingMode,
    setDefault: (ComicReadingMode) -> Unit, set: (ComicPreferences) -> Unit, details: () -> Unit, close: () -> Unit) {
    AppSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().comicPanelHeight().testTag("comic-settings-panel")) {
            ComicSheetHeader("漫画显示设置", "调整即时生效 · 显示选项仅应用于本书", close)
            HorizontalDivider()
            AppScrollColumn(Modifier.weight(1f), contentModifier = Modifier.padding(top = 6.dp, bottom = 16.dp)) {
                ReaderPreferenceSection("阅读方向", "当前 · ${(prefs.mode ?: default).label}", Icons.Outlined.ImportContacts) {
                    ChoiceRow("本书阅读方向", listOf("跟随默认") + ComicReadingMode.entries.map { it.label },
                        prefs.mode?.let { ComicReadingMode.entries.indexOf(it) + 1 } ?: 0) { index ->
                        set(prefs.copy(mode = ComicReadingMode.entries.getOrNull(index - 1)))
                    }
                    ChoiceRow("全局默认方向", ComicReadingMode.entries.map { it.label }, ComicReadingMode.entries.indexOf(default)) {
                        setDefault(ComicReadingMode.entries[it])
                    }
                    Text("全局默认用于未单独指定方向的漫画。", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ReaderPreferenceSection("分页显示", "以下选项用于左右翻页，纵向条漫保留原图布局", Icons.Outlined.PhotoSizeSelectLarge) {
                    TogglePreference("适合宽度", "将漫画放大至屏幕宽度，可拖动查看上下内容", prefs.fitWidth) { set(prefs.copy(fitWidth = it)) }
                    TogglePreference("裁去白边", "减少页面四周的空白，保留画面内容", prefs.cropBorders) { set(prefs.copy(cropBorders = it)) }
                    TogglePreference("拆分横跨页", "按阅读方向依次显示横向跨页的两侧", prefs.splitWide) { set(prefs.copy(splitWide = it)) }
                }
                ReaderPreferenceSection("书籍", "查看作品资料与完整目录", Icons.Outlined.Info) {
                    MenuRow("书籍详情与目录", "返回作品页面", Icons.AutoMirrored.Outlined.MenuBook, details,
                        trailing = { Icon(Icons.Outlined.ChevronRight, null) })
                }
            }
        }
    }
}
