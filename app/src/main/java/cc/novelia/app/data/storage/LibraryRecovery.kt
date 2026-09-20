package cc.novelia.app.data.storage

import cc.novelia.app.data.model.LibraryState

data class LibraryRecoveryIssue(val hasLastGood: Boolean) {
    val message: String get() = if (hasLastGood)
        "本地资料无法读取，原文件已保护。正在显示最后良好副本；恢复前修改不会保存。"
    else "本地资料无法读取，原文件已保护。请从备份恢复；恢复前修改不会保存。"
}

internal data class LoadedLibrary(val state: LibraryState, val issue: LibraryRecoveryIssue? = null)

/** A missing first-run file is different from an unreadable existing file. Never silently reset it. */
internal fun loadLibraryState(exists: Boolean, read: () -> String, readLastGood: () -> String,
    decode: (String) -> LibraryState = { appJson.decodeFromString<LibraryState>(it) }): LoadedLibrary {
    if (!exists) return LoadedLibrary(LibraryState())
    return try { LoadedLibrary(decode(read())) }
    catch (_: Exception) {
        val recovered = runCatching { decode(readLastGood()) }.getOrNull()
        LoadedLibrary(recovered ?: LibraryState(), LibraryRecoveryIssue(recovered != null))
    }
}
