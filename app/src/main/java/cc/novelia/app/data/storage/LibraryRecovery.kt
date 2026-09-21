package cc.novelia.app.data.storage

import cc.novelia.app.data.model.LibraryState

data class LibraryRecoveryIssue(val hasLastGood: Boolean) {
    val message: String get() = if (hasLastGood)
        "本地资料无法读取，原文件已保护。正在显示最后良好副本；恢复前修改不会保存。"
    else "本地资料无法读取，原文件已保护。请从备份恢复；恢复前修改不会保存。"
}

internal data class LoadedLibrary(val state: LibraryState, val issue: LibraryRecoveryIssue? = null)

/**
 * 区分首次启动与已有资料损坏：只有完全没有状态文件时才正常创建空书库。
 * 主副本解析失败后尝试最后良好副本，但无论回退是否成功，都返回恢复标记，
 * 由 LocalStore 阻止普通写入，避免把空状态或旧状态自动覆盖到用户原始资料上。
 */
internal fun loadLibraryState(exists: Boolean, read: () -> String, readLastGood: () -> String,
    decode: (String) -> LibraryState = { appJson.decodeFromString<LibraryState>(it) }): LoadedLibrary {
    if (!exists) return LoadedLibrary(LibraryState())
    return try { LoadedLibrary(decode(read())) }
    catch (_: Exception) {
        val recovered = runCatching { decode(readLastGood()) }.getOrNull()
        LoadedLibrary(recovered ?: LibraryState(), LibraryRecoveryIssue(recovered != null))
    }
}
