package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class LocalChapter(val id: String, val title: String, val paragraphs: List<String>)

/**
 * 本地文档既可表示完整的导入/导出内容，也可表示阅读器使用的轻量目录。
 * chapterFiles 非空时，chapters 主要承载标题和顺序，正文按哈希索引延迟读取。
 * images 在可移植格式中保存 Base64 内容，落盘后由独立图片文件承担，不应长期装入内存。
 */
@Serializable data class LocalDocument(val id: String, val name: String, val format: String, val chapters: List<LocalChapter>, val importedAt: Long = System.currentTimeMillis(), val images: Map<String, String> = emptyMap(), val coverImage: String? = null, val sourceHash: String = "",
    /** Content-addressed chapter files; empty for the portable/legacy full-document format. */
    val chapterFiles: Map<String, String> = emptyMap())
