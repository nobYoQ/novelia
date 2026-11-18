package cc.novelia.app.data.updates

internal data class ShelfCheckReport(val finishedAt: Long, val updated: Int, val failed: Int, val total: Int?) {
    val message: String get() = when {
        total == null -> (if(updated > 0) "上次有 $updated 本触发更新提醒" else "上次检查已结束") +
            (if(failed > 0) "，$failed 本检查失败" else "")
        total == 0 -> "没有可检查的在线书籍"
        failed == total -> "检查失败：$failed 本书均未能检查，请重试"
        failed > 0 && updated == 0 -> "已成功检查的书籍没有更新，另有 $failed 本检查失败"
        failed > 0 -> "发现 $updated 本有更新，另有 $failed 本检查失败"
        updated > 0 -> "检查完成：发现 $updated 本有更新"
        else -> "检查完成：未发现更新"
    }

    fun encode() = listOfNotNull(finishedAt, updated, failed, total).joinToString("|")

    companion object {
        const val KEY = "updates:last"

        fun decode(value: String?): ShelfCheckReport? {
            val fields = value?.split('|') ?: return null
            if(fields.size !in 3..4) return null
            val time = fields[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val updated = fields[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val failed = fields[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            if(fields.size == 3) return ShelfCheckReport(time, updated, failed, null)
            val total = fields[3].toIntOrNull() ?: return null
            if(total < failed || updated > total - failed) return null
            return ShelfCheckReport(time, updated, failed, total)
        }
    }
}
