package cc.novelia.app.files.importing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ImportStatus(val label: String) {
    Pending("尚未处理"), Running("正在处理"), Success("成功"), Duplicate("重复"), Failed("失败")
}

internal data class ImportItem(
    val source: String,
    val name: String,
    val status: ImportStatus = ImportStatus.Pending,
    val detail: String = "",
    val bookKey: String? = null,
)

/** 每项独立失败；取消不能被当成文件错误，尚未完成的项仍可继续。 */
internal suspend fun runDocumentImportBatch(
    initial: List<ImportItem>,
    failedOnly: Boolean = false,
    onChange: (List<ImportItem>) -> Unit,
    errorMessage: (Exception) -> String = { it.message ?: "无法导入文件" },
    import: suspend (ImportItem, (String) -> Unit) -> DocumentImportResult,
): List<ImportItem> {
    var items = initial.toList()
    val targets = initial.indices.filter { index ->
        initial[index].status == if (failedOnly) ImportStatus.Failed else ImportStatus.Pending
    }
    for (index in targets) {
        currentCoroutineContext().ensureActive()
        val item = items[index]
        fun update(value: ImportItem) {
            items = items.toMutableList().also { it[index] = value }
            onChange(items)
        }
        update(item.copy(status = ImportStatus.Running, detail = "准备导入"))
        try {
            val result = import(item) { stage -> update(item.copy(status = ImportStatus.Running, detail = stage)) }
            update(item.copy(
                status = if (result.imported) ImportStatus.Success else ImportStatus.Duplicate,
                detail = if (result.imported) "已加入书架" else "书架中已有相同文件",
                bookKey = result.ref.key,
            ))
        } catch (cancelled: CancellationException) {
            update(item.copy(status = ImportStatus.Pending, detail = "已暂停，可继续处理"))
            throw cancelled
        } catch (error: Exception) {
            update(item.copy(status = ImportStatus.Failed, detail = errorMessage(error), bookKey = null))
        }
    }
    return items
}
