package cc.novelia.app.ui.markdown

import androidx.compose.runtime.*
import cc.novelia.app.data.storage.LocalStore

/**
 * 在离开编辑器时读取实时文本，覆盖尚未来得及重组或防抖保存的最后一次输入。
 * 提交成功只有在当前文本仍等于已提交文本时才删除草稿；请求期间新增的输入继续保留。
 * completed 防止成功清稿后，组件销毁回调又把已经提交的内容写回草稿。
 */
internal class DraftPersistence(private val read: () -> String, private val write: (String?) -> Unit, private var baseline: String? = null) {
    private var completed = false

    fun save() { if (!completed) write(read().takeUnless { it == baseline }) }

    fun submittedSuccessfully(submitted: String) {
        // 服务器现已保存提交快照；请求期间改回旧原文也属于未提交的改动。
        baseline = submitted
        if (read() == submitted) {
            completed = true
            write(null)
        } else save()
    }
}

/** 已有文档可传入原始快照，只持久化实际改动，改回原文时清理草稿。 */
@Composable internal fun rememberDraftPersistence(store: LocalStore, key: String, baseline: String? = null, read: () -> String): DraftPersistence {
    return androidx.compose.runtime.key(store, key) {
        val latestRead by rememberUpdatedState(read)
        val persistence = remember {
            DraftPersistence(read = { latestRead() }, write = { value ->
                store.update { state -> state.copy(drafts = if (value == null) state.drafts - key else state.drafts + (key to value)) }
            }, baseline = baseline)
        }
        DisposableEffect(persistence) { onDispose { persistence.save() } }
        persistence
    }
}
