package cc.novelia.app.ui.components.base

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import cc.novelia.app.ui.theme.DefaultShapes
import cc.novelia.app.ui.theme.appControlShape
import cc.novelia.app.ui.theme.isMonochromeTheme

@Composable
internal fun appTextFieldColors(): TextFieldColors {
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerHighest
    // 黑白主题的各层表面都是白色，用 Material 的下划线标出输入区域。
    val indicator = if (isMonochromeTheme()) scheme.outline else Color.Transparent
    return TextFieldDefaults.colors(
        unfocusedContainerColor = fill,
        focusedContainerColor = scheme.primary.copy(alpha = .12f).compositeOver(fill),
        errorContainerColor = scheme.error.copy(alpha = .10f).compositeOver(fill),
        disabledContainerColor = scheme.onSurface.copy(alpha = .04f).compositeOver(scheme.surface),
        unfocusedIndicatorColor = indicator,
        focusedIndicatorColor = indicator,
        disabledIndicatorColor = Color.Transparent,
    )
}

@Composable
internal fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = DefaultShapes.large,
    colors: TextFieldColors = appTextFieldColors(),
) = TextField(
    value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
    textStyle = textStyle, label = label, placeholder = placeholder, leadingIcon = leadingIcon,
    trailingIcon = trailingIcon, suffix = suffix, supportingText = supportingText, isError = isError,
    visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines, shape = appControlShape(shape), colors = colors,
)

@Composable
internal fun AppTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) = TextField(
    value = value, onValueChange = onValueChange, modifier = modifier, readOnly = readOnly,
    textStyle = textStyle, label = label, placeholder = placeholder, supportingText = supportingText,
    isError = isError, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines,
    shape = appControlShape(DefaultShapes.large), colors = appTextFieldColors(),
)
