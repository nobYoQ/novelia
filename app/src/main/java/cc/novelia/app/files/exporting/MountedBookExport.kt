package cc.novelia.app.files.exporting

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.LocalStore
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PreparedBookExport(val id: String, val name: String)

internal suspend fun BookExportFiles.prepareMounted(store: LocalStore, parent: BookCard, refs: List<BookRef>): PreparedBookExport =
    withContext(Dispatchers.IO) {
        require(refs.isNotEmpty() && refs.distinct().size == refs.size)
        val mounted = store.state.value.books.filter { it.parentWenkuKey == parent.ref.key && it.book.ref.isLocal }.map { it.book.ref }.toSet()
        require(mounted.containsAll(refs)) { "挂载分卷已变化，请重新选择" }
        val volumes = refs.map { it to prepareLocalBookExport(store, it.id) }
        val single = volumes.singleOrNull()
        val name = single?.second?.fileName ?: localBookExportFileName("${parent.title}（${volumes.size}卷）", "zip", "zip")
        val work = coroutineContext
        val id = create { output ->
            if(single != null) exportLocalBook(store, single.first.id, single.second.original, { output }) { work.ensureActive() }
            else ZipOutputStream(output, Charsets.UTF_8).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                val used = mutableSetOf<String>()
                val folder = archiveFolder(mutableSetOf(), listOf(parent), parent.title)
                for((ref, volume) in volumes) {
                    work.ensureActive()
                    val label = safeArchiveName(volume.fileName, "分卷.txt")
                    val entry = uniqueArchiveName(used, label.substringBeforeLast('.'), "." + label.substringAfterLast('.'), "$folder/")
                    zip.putNextEntry(ZipEntry(entry))
                    // 单卷导出会关闭自己的输出流，ZIP 的生命周期覆盖所有分卷。
                    val entryStream = object : FilterOutputStream(zip) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = zip.write(bytes, offset, length)
                        override fun close() = Unit
                    }
                    exportLocalBook(store, ref.id, volume.original, { entryStream }) { work.ensureActive() }
                    zip.closeEntry()
                }
            }
        }
        PreparedBookExport(id, name)
    }
