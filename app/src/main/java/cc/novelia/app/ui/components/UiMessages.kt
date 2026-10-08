package cc.novelia.app.ui.components

import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.network.EchIOException
import cc.novelia.app.data.updates.AppReleaseException
import cc.novelia.app.ui.markdown.format
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun Throwable?.friendlyMessage(): String = when(this) { is ApiException -> message; is AppReleaseException -> message; is EchIOException -> message ?: "ECH 连接失败，请运行连接诊断"; is java.net.UnknownHostException -> "网络不可用，请检查连接。已缓存的章节仍可在书架中阅读。"; is java.net.SocketTimeoutException -> "连接超时，请稍后重试"; is IllegalArgumentException -> message?.take(200) ?: "输入内容或文件格式不符合要求"; else -> "操作未完成，请检查网络或文件内容后重试" }

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
fun displayDate(seconds: Long): String = runCatching { dateFormatter.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())) }.getOrDefault("")
