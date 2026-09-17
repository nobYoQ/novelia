package cc.novelia.app

import cc.novelia.app.data.WenkuDetail
import cc.novelia.app.data.WenkuVolume
import cc.novelia.app.ui.DraftPersistence
import cc.novelia.app.ui.editablePayload
import org.junit.Assert.*
import org.junit.Test

class EditorStateRegressionTest {
    @Test fun leavingBeforeDebounceSavesLatestInput() {
        var input = "已保存"
        var stored: String? = input
        val drafts = DraftPersistence({ input }, { stored = it })
        input = "已保存及最后一次输入"
        drafts.save() // The same synchronous callback used by onDispose.
        assertEquals(input, stored)
    }

    @Test fun successfulSubmissionCannotBeResurrectedByDisposeOrPendingSave() {
        val input = "已发送正文"
        var stored: String? = input
        val drafts = DraftPersistence({ input }, { stored = it })
        drafts.submittedSuccessfully(input)
        drafts.save()
        drafts.save()
        assertNull(stored)
    }

    @Test fun editsDuringSubmissionRemainDraftsWhenNavigatingAway() {
        var input = "提交的版本"
        var stored: String? = input
        val drafts = DraftPersistence({ input }, { stored = it })
        val submitted = input
        input += "，以及响应前的新输入"
        drafts.submittedSuccessfully(submitted)
        drafts.save()
        assertEquals(input, stored)
    }

    @Test fun wenkuConflictCheckCoversEverySubmittedField() {
        val original = WenkuDetail(title = "原文", titleZh = "译名", cover = "old-cover", volumes = listOf(WenkuVolume(asin = "volume")))
        val changes = listOf(
            original.copy(title = "另一原文"),
            original.copy(titleZh = "另一译名"),
            original.copy(cover = "https://example.invalid/cover.jpg"),
            original.copy(authors = listOf("作者")),
            original.copy(artists = listOf("画师")),
            original.copy(level = "轻文学"),
            original.copy(introduction = "简介"),
            original.copy(keywords = listOf("标签")),
            original.copy(volumes = listOf(WenkuVolume(asin = "volume", coverHires = "new-cover")))
        )
        changes.forEach { changed -> assertNotEquals(original.editablePayload(), changed.editablePayload()) }
        assertEquals(setOf("title", "titleZh", "cover", "authors", "artists", "level", "introduction", "keywords", "volumes"), original.editablePayload().keys)
    }

    @Test fun wenkuReadCountersAndSeparateGlossaryDoNotCreateFalseConflicts() {
        val original = WenkuDetail(title = "原文")
        val latest = original.copy(visited = 100, glossary = mapOf("語" to "词"), favored = "folder")
        assertEquals(original.editablePayload(), latest.editablePayload())
    }
}
