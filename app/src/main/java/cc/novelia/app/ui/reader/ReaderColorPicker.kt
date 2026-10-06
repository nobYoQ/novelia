@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.novelia.app.ui.reader

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.AppAlertDialog
import kotlin.math.roundToInt

private val CommonColors = listOf(0x000000L, 0xFFFFFFL, 0x282E27L, 0xF4ECD8L, 0xE8F2E5L, 0x141A16L, 0xDDE5DCL, 0xDED2B8L)
private val HueColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
private fun rgb(value: Long) = Color(0xFF000000L or value)
private fun hex(value: Long) = "#%06X".format(value)
private fun parseColor(input: String): Long? = input.trim().removePrefix("#")
    .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLong(16)
private fun hsv(value: Long) = FloatArray(3).also { AndroidColor.colorToHSV((0xFF000000L or value).toInt(), it) }
private fun rgb(hue: Float, saturation: Float, brightness: Float): Long =
    AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFFL

/** 颜色在弹窗内预览，确认后才更新阅读设置。 */
@Composable internal fun ReaderColorPicker(label: String, initial: Long, onDismiss: () -> Unit, onApply: (Long) -> Unit) {
    val initialHsv = remember(initial) { hsv(initial) }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    var input by rememberSaveable { mutableStateOf(hex(initial)) }
    val parsed = parseColor(input)
    val pickedColor = parsed ?: rgb(hue, saturation, brightness)
    fun selectColor(value: Long) {
        val next = hsv(value)
        // 黑白灰没有色相，保留用户此前选中的色相以便继续调色。
        if(next[1] > 0f) hue = next[0]
        saturation = next[1]
        brightness = next[2]
    }
    fun setHsv(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h.coerceIn(0f, 360f)
        saturation = s.coerceIn(0f, 1f)
        brightness = v.coerceIn(0f, 1f)
        input = hex(rgb(hue, saturation, brightness))
    }
    AppAlertDialog(onDismissRequest = onDismiss, title = { Text(label) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = rgb(pickedColor), shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(40.dp).testTag("reader-picked-color")) {}
                Text(hex(pickedColor), style = MaterialTheme.typography.titleMedium)
            }
            Text("在取色盘上点选或拖动", style = MaterialTheme.typography.bodySmall)
            SaturationBrightnessPlane(hue, saturation, brightness) { s, v -> setHsv(s = s, v = v) }
            Text("色相 ${hue.roundToInt()}°", style = MaterialTheme.typography.labelLarge)
            Slider(hue, { setHsv(h = it) }, valueRange = 0f..360f,
                modifier = Modifier.fillMaxWidth().testTag("reader-color-hue").semantics { contentDescription = "色相" },
                thumb = {
                    Surface(color = rgb(rgb(hue, 1f, 1f)), shape = CircleShape,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(24.dp)) {}
                }, track = {
                    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                        drawRoundRect(Brush.horizontalGradient(HueColors), cornerRadius = CornerRadius(6.dp.toPx()))
                    }
                })
            Text("饱和度 ${(saturation * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(saturation, { setHsv(s = it) }, modifier = Modifier.fillMaxWidth().testTag("reader-color-saturation")
                .semantics { contentDescription = "饱和度" })
            Text("明度 ${(brightness * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(brightness, { setHsv(v = it) }, modifier = Modifier.fillMaxWidth().testTag("reader-color-brightness")
                .semantics { contentDescription = "明度" })
            OutlinedTextField(input, {
                input = it
                parseColor(it)?.let(::selectColor)
            }, label = { Text("十六进制颜色") }, singleLine = true, isError = parsed == null,
                supportingText = { Text("输入 6 位 RGB 色值，例如 #F4ECD8") },
                modifier = Modifier.fillMaxWidth().testTag("reader-color-input"))
            Text("常用颜色", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CommonColors.forEach { color ->
                    Surface(onClick = { selectColor(color); input = hex(color) }, color = rgb(color), shape = CircleShape,
                        border = BorderStroke(if(pickedColor == color) 2.dp else 1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "常用颜色 ${hex(color)}"; selected = pickedColor == color }) {
                        if(pickedColor == color) Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, tint = if(rgb(color).luminance() > .5f) Color.Black else Color.White)
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text("应用颜色") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable private fun SaturationBrightnessPlane(hue: Float, saturation: Float, brightness: Float,
    onPick: (Float, Float) -> Unit) {
    val latestPick by rememberUpdatedState(onPick)
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f).clip(MaterialTheme.shapes.small)
        .testTag("reader-color-plane").semantics {
            contentDescription = "饱和度与明度取色盘"
            stateDescription = "饱和度 ${(saturation * 100).roundToInt()}%，明度 ${(brightness * 100).roundToInt()}%"
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                fun pick(position: Offset) = latestPick((position.x / size.width).coerceIn(0f, 1f),
                    (1f - position.y / size.height).coerceIn(0f, 1f))
                down.consume()
                pick(down.position)
                do {
                    val pointer = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if(pointer.pressed) pick(pointer.position)
                    pointer.consume()
                } while(pointer.pressed)
            }
        }) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, rgb(rgb(hue, 1f, 1f)))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val radius = 7.dp.toPx().coerceAtMost(size.minDimension / 2f)
        val marker = Offset((saturation * size.width).coerceIn(radius, size.width - radius),
            ((1f - brightness) * size.height).coerceIn(radius, size.height - radius))
        drawCircle(Color.Black, radius, marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, radius, marker, style = Stroke(1.5.dp.toPx()))
    }
}
