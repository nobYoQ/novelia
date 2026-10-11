package cc.novelia.app.ui.components.book

import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.sync.CloudSyncStatus
import cc.novelia.app.data.sync.pendingBookKey
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class BookSyncPhase { Pending, Syncing, Failed, LoginRequired }
internal data class BookSyncRowState(val phase: BookSyncPhase, val label: String, val detail: String)

internal fun bookSyncStates(
    account: String?, pending: List<PendingAction>, status: CloudSyncStatus,
    inFlight: Collection<PendingAction> = emptyList(), automatic: Boolean = true,
): Map<String, BookSyncRowState> {
    if (account == null) return emptyMap()
    val active = inFlight.filter { it.account == account }.associateBy { it.id }
    return (pending.filter { it.account == account } + active.values).distinctBy { it.id }
        .mapNotNull { action -> pendingBookKey(action)?.let { it to action } }
        .groupBy({ it.first }, { it.second }).mapValues { (_, actions) ->
            val operations = actions.map { when {
                it.path.startsWith("user/read-history/") -> "阅读进度"
                it.method == "DELETE" -> "取消收藏"
                else -> "收藏"
            } }.distinct().joinToString("、")
            val errors = actions.mapNotNull { status.failures[it.id] }.distinct()
            when {
                actions.any { it.id in active } -> BookSyncRowState(BookSyncPhase.Syncing, "${operations}同步中", "正在提交本书的云端操作")
                status.requiresLogin -> BookSyncRowState(BookSyncPhase.LoginRequired, "${operations}等待登录", "登录已失效，操作仍保存在此设备")
                errors.isNotEmpty() -> BookSyncRowState(BookSyncPhase.Failed, "${operations}同步失败",
                    errors.joinToString("；").let { if (automatic) it else it.replace("将自动重试", "可手动重试").replace("稍后自动重试", "请稍后手动重试") })
                else -> BookSyncRowState(BookSyncPhase.Pending, "${operations}待同步", if (automatic) "等待联网自动同步" else "等待手动同步")
            }
        }
}

internal data class BookSyncPresentation(
    val books: Map<String, BookSyncRowState> = emptyMap(),
    val retry: (BookRef) -> Unit = {},
    val login: (BookRef) -> Unit = {},
)
internal val LocalBookSyncPresentation = compositionLocalOf { BookSyncPresentation() }
private data class BookSyncInputs(val pending: List<PendingAction>, val statuses: Map<String, CloudSyncStatus>, val automatic: Boolean)

@Composable internal fun rememberBookSyncPresentation(c: AppController): BookSyncPresentation {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val source = remember(c.store) { c.store.state.map { BookSyncInputs(it.pending, it.syncStatus, it.autoSync) }.distinctUntilChanged() }
    val inputs by source.collectAsStateWithLifecycle(initialValue = remember(c.store) {
        c.store.state.value.let { BookSyncInputs(it.pending, it.syncStatus, it.autoSync) }
    })
    val active by c.api.cloudMutations.inFlight.collectAsStateWithLifecycle()
    val account = profile?.username
    return remember(c, account, inputs, active) {
        BookSyncPresentation(
            books = bookSyncStates(account, inputs.pending, inputs.statuses[account] ?: CloudSyncStatus(), active.values, inputs.automatic),
            retry = { ref -> if (c.session.profile.value?.username == account && account != null) c.syncBook(ref) },
            login = { ref ->
                if (c.session.profile.value?.username == account && account != null) {
                    c.afterLogin = { if (c.session.profile.value?.username == account) c.syncBook(ref) }
                    c.go("login")
                }
            },
        )
    }
}

@Composable internal fun BookSyncIndicator(ref: BookRef) {
    val presentation = LocalBookSyncPresentation.current
    val state = presentation.books[ref.key] ?: return
    val error = state.phase == BookSyncPhase.Failed || state.phase == BookSyncPhase.LoginRequired
    Column(Modifier.fillMaxWidth().testTag("book-sync-${ref.key}").semantics { stateDescription = state.label }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            if (state.phase != BookSyncPhase.Syncing) AppTextButton(
                onClick = { if (state.phase == BookSyncPhase.LoginRequired) presentation.login(ref) else presentation.retry(ref) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (state.phase == BookSyncPhase.LoginRequired) "重新登录" else "重试同步") }
        }
        Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
