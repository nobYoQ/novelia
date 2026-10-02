package cc.novelia.app.data.network

import cc.novelia.app.data.storage.appJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Fixed metadata only: never add raw URLs, header maps, bodies or exception messages here. */
@Serializable internal data class NetworkLogEvent(
    val timeMs: Long = System.currentTimeMillis(),
    val id: String = "",
    val scope: String = "system",
    val run: String? = null,
    val route: String = "",
    val host: String = "",
    val target: String = "",
    val method: String = "",
    val stage: String,
    val outcome: String,
    val elapsedMs: Long = 0,
    val durationMs: Long? = null,
    val reason: String? = null,
    val status: Int? = null,
    val bytes: Long? = null,
    val protocol: String? = null,
    val family: String? = null,
    val serverIp: String? = null,
    val proxy: String? = null,
    val resolver: String? = null,
    val attempt: Int? = null,
    val exchange: Int? = null,
    val environment: Map<String, String>? = null,
    val connectTimeoutMs: Int? = null,
    val readTimeoutMs: Int? = null,
    val writeTimeoutMs: Int? = null
)

/** Called only by the recorder's single IO writer; bounded even during long downloads. */
internal class NetworkLogStore(private val directory: File, private val maxFileBytes: Long = 512 * 1024) {
    private val current get() = File(directory, "network-current.jsonl")
    private val previous get() = File(directory, "network-previous.jsonl")
    private val report get() = File(directory, "latest-report.txt")

    @Synchronized fun append(event: NetworkLogEvent) {
        directory.mkdirs()
        val line = (appJson.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8)
        require(line.size <= maxFileBytes)
        if (current.length() + line.size > maxFileBytes) {
            check(!previous.exists() || previous.delete())
            check(current.renameTo(previous))
        }
        current.appendBytes(line)
    }

    @Synchronized fun saveReport(text: String) {
        directory.mkdirs()
        report.writeText(text.take(64 * 1024), Charsets.UTF_8)
    }

    @Synchronized fun snapshot(): Map<String, ByteArray> = buildMap {
        for (file in listOf(previous, current)) {
            if (file.isFile && file.length() <= maxFileBytes) put(file.name, file.readBytes())
        }
        if (report.isFile && report.length() <= 256 * 1024) put("diagnosis.txt", report.readBytes())
    }

    @Synchronized fun clear() {
        for (file in listOf(current, previous, report)) check(!file.exists() || file.delete())
    }
}

internal fun networkLogArchive(files: Map<String, ByteArray>, environment: String, dropped: Long): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output, Charsets.UTF_8).use { zip ->
        val entries = files + mapOf(
            "environment.json" to environment.toByteArray(Charsets.UTF_8),
            "README.txt" to """
                Novelia 网络诊断日志 · schema 1
                diagnosis.txt：最近一次诊断的逐项结果；取消或超时也保留已完成结果。
                environment.json：导出时的设备、应用和网络概况。
                network-*.jsonl：按 timeMs 排序，id 相同的事件属于同一次请求；scope=diagnostic 为主动检测。
                route=ECH 使用应用的 ECH 传输，route=direct 使用系统 DNS / OkHttp。
                重点阶段：dns / doh_ech、tcp、tls、ech、response_headers、body、call。
                outcome=closed 表示提前关闭，不能等同于文件完整下载；HTTP 错误不等同于连接失败。
                native.* 事件的 elapsedMs 从原生请求创建时算起，其他事件从 OkHttp Call 创建时算起。
                只记录预定义接口类别，不保存完整地址、查询参数、账号、Cookie、令牌、正文或原始异常。
                未记录 SSID、本机 IP、运营商、设备序列号；设备型号用于分析设备差异。
                原生解析器当前只选 IPv4；直连可能使用 IPv6，两条路径也可能使用不同代理。
                ECH 新连接使用独立连接池，后续同域检查可能复用；业务连接池不会被诊断重置。
                超时、证书或重置是失败类型，不足以单独证明网络封锁。
                队列丢弃或写盘失败的事件数：$dropped
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        entries.forEach { (name, bytes) ->
            require(name in setOf("network-current.jsonl", "network-previous.jsonl", "diagnosis.txt", "environment.json", "README.txt"))
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    }
    return output.toByteArray()
}
