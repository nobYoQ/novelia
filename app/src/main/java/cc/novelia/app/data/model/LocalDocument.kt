package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

@Serializable data class LocalChapter(val id: String, val title: String, val paragraphs: List<String>)

@Serializable data class LocalDocument(val id: String, val name: String, val format: String, val chapters: List<LocalChapter>, val importedAt: Long = System.currentTimeMillis(), val images: Map<String, String> = emptyMap(), val coverImage: String? = null, val sourceHash: String = "",
    /** Content-addressed chapter files; empty for the portable/legacy full-document format. */
    val chapterFiles: Map<String, String> = emptyMap())
