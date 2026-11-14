package cc.novelia.app.ui.components.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.network.isRemoteImage
import coil.request.ImageRequest

/** 在线正文和下载 EPUB 共用；长链接可滚动、复制，并为重试按钮保留空间。 */
@Composable internal fun IllustrationLoadError(
    model: Any?, foreground: Color, imageLabel: String = "插图", retryButton: @Composable () -> Unit,
) {
    val data = (model as? ImageRequest)?.data ?: model
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${imageLabel}暂时无法加载", style = MaterialTheme.typography.bodyMedium,
                color = foreground, textAlign = TextAlign.Center)
            if(isRemoteImage(data)) {
                Text("本图片是外部链接图片，请检查网络环境后重试。", style = MaterialTheme.typography.bodyMedium,
                    color = foreground, textAlign = TextAlign.Center)
                SelectionContainer {
                    Text("图片链接：$data", style = MaterialTheme.typography.bodySmall,
                        color = foreground, textAlign = TextAlign.Center)
                }
            }
        }
        retryButton()
    }
}
