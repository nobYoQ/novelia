package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import cc.novelia.app.ui.markdown.format
import java.text.DateFormat
import java.util.Date

internal fun syncTime(value: Long) = if(value <= 0) "尚无记录" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
