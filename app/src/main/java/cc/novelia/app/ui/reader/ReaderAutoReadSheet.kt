package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppScrollColumn
import kotlin.math.roundToInt

@Composable internal fun ReaderAutoReadSheet(
    settings: ReaderSettings, discrete: Boolean, onDismiss: () -> Unit,
    onSettings: (ReaderSettings) -> Unit, onStart: () -> Unit,
) {
    var speed by remember { mutableFloatStateOf(settings.resolvedAutoReadSpeed) }
    var interval by remember { mutableFloatStateOf(settings.resolvedAutoReadIntervalSeconds.toFloat()) }
    ReaderSheet(onDismissRequest = onDismiss) {
        AppScrollColumn(contentModifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("自动阅读", style = MaterialTheme.typography.titleLarge)
            Text(if(discrete) "每次翻过一页或一屏，停留后继续。" else "正文匀速向上滚动，到章末停留后自动接续下一章。")
            if(!discrete) {
                Text("滚动速度：${speed.roundToInt()} dp/秒")
                Slider(speed, { speed = it }, valueRange = 5f..80f, modifier = Modifier.testTag("reader-auto-speed"),
                    onValueChangeFinished = { onSettings(settings.copy(autoReadSpeed = speed.roundToInt().toFloat())) })
            }
            Text("${if(discrete) "每页 / 屏" else "章末"}停留：${interval.roundToInt()} 秒")
            Slider(interval, { interval = it }, valueRange = 5f..120f, steps = 22, modifier = Modifier.testTag("reader-auto-interval"),
                onValueChangeFinished = { onSettings(settings.copy(autoReadIntervalSeconds = interval.roundToInt())) })
            Text("触碰正文、打开菜单、切到后台或离开阅读器会暂停。自动阅读期间保持亮屏，开启朗读时也会暂停。", style = MaterialTheme.typography.bodySmall)
            AppButton(onClick = {
                onSettings(settings.copy(autoReadSpeed = speed.roundToInt().toFloat(), autoReadIntervalSeconds = interval.roundToInt()))
                onStart()
            }, modifier = Modifier.fillMaxWidth()) { Text("开始自动阅读") }
        }
    }
}
