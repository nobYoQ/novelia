package cc.novelia.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.LocalSquareCorners

/** 保留 Material 的尺寸、颜色及父行接管点击的规则，仅替换没有 shape 参数的绘制。 */
@Composable fun AppSwitch(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: SwitchColors = SwitchDefaults.colors(),
) {
    if(!LocalSquareCorners.current) {
        Switch(checked, onCheckedChange, modifier, enabled = enabled, colors = colors)
        return
    }
    val track = if(enabled) {
        if(checked) colors.checkedTrackColor else colors.uncheckedTrackColor
    } else if(checked) colors.disabledCheckedTrackColor else colors.disabledUncheckedTrackColor
    val thumb = if(enabled) {
        if(checked) colors.checkedThumbColor else colors.uncheckedThumbColor
    } else if(checked) colors.disabledCheckedThumbColor else colors.disabledUncheckedThumbColor
    val border = if(enabled) {
        if(checked) colors.checkedBorderColor else colors.uncheckedBorderColor
    } else if(checked) colors.disabledCheckedBorderColor else colors.disabledUncheckedBorderColor
    val input = if(onCheckedChange == null) Modifier else Modifier.minimumInteractiveComponentSize()
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    Box(modifier.then(input).wrapContentSize(Alignment.Center).size(52.dp, 32.dp)
        .background(track).border(BorderStroke(2.dp, border)).padding(if(checked) 4.dp else 8.dp)) {
        Box(Modifier.align(if(checked) Alignment.CenterEnd else Alignment.CenterStart)
            .size(if(checked) 24.dp else 16.dp).background(thumb))
    }
}

@Composable fun AppCheckbox(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: CheckboxColors = CheckboxDefaults.colors(),
) {
    if(!LocalSquareCorners.current) {
        Checkbox(checked, onCheckedChange, modifier, enabled, colors)
        return
    }
    val background = if(enabled) {
        if(checked) colors.checkedBoxColor else colors.uncheckedBoxColor
    } else if(checked) colors.disabledCheckedBoxColor else colors.disabledUncheckedBoxColor
    val border = if(enabled) {
        if(checked) colors.checkedBorderColor else colors.uncheckedBorderColor
    } else if(checked) colors.disabledBorderColor else colors.disabledUncheckedBorderColor
    val input = if(onCheckedChange == null) Modifier else Modifier.minimumInteractiveComponentSize()
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
    Box(modifier.then(input).padding(2.dp).size(20.dp).background(background).border(2.dp, border)) {
        if(checked) Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply {
                moveTo(size.width * .2f, size.height * .5f)
                lineTo(size.width * .4f, size.height * .7f)
                lineTo(size.width * .8f, size.height * .3f)
            }
            drawPath(path, colors.checkedCheckmarkColor, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable fun AppRadioButton(
    selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    if(!LocalSquareCorners.current) {
        RadioButton(selected, onClick, modifier, enabled)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val color = (if(selected) scheme.primary else scheme.onSurfaceVariant).let {
        if(enabled) it else scheme.onSurface.copy(alpha = .38f)
    }
    val input = if(onClick == null) Modifier else Modifier.minimumInteractiveComponentSize()
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Box(modifier.then(input).padding(2.dp).size(20.dp).border(2.dp, color), contentAlignment = Alignment.Center) {
        if(selected) Box(Modifier.size(10.dp).background(color))
    }
}
