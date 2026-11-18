@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.FavoriteSyncPhase
import cc.novelia.app.data.library.FavoriteSyncPlan
import cc.novelia.app.data.library.FavoriteSyncPriority
import cc.novelia.app.data.library.FavoriteSyncProgress
import cc.novelia.app.ui.components.base.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class SyncWork { Checking, Applying }

@Composable internal fun FavoriteSyncSheet(
    account: String,
    onCheck: suspend (FavoriteSyncPriority, (FavoriteSyncProgress) -> Unit) -> FavoriteSyncPlan,
    onApply: suspend (FavoriteSyncPlan, (FavoriteSyncProgress) -> Unit, (Int, Int) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onFinished: (String) -> Unit,
) {
    var priority by rememberSaveable { mutableStateOf(FavoriteSyncPriority.Local) }
    var plan by remember { mutableStateOf<FavoriteSyncPlan?>(null) }
    var work by remember { mutableStateOf<SyncWork?>(null) }
    var progress by remember { mutableStateOf(FavoriteSyncProgress(FavoriteSyncPhase.ReadWeb)) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val applying by rememberUpdatedState(work == SyncWork.Applying)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !applying || it != SheetValue.Hidden })

    fun check() {
        if(work != null) return
        work = SyncWork.Checking; progress = FavoriteSyncProgress(FavoriteSyncPhase.ReadWeb); error = null
        scope.launch {
            try { plan = onCheck(priority) { progress = it } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { error = failure.friendlyMessage() }
            finally { work = null }
        }
    }
    fun apply(reviewed: FavoriteSyncPlan) {
        if(work != null || reviewed.changes.isEmpty()) return
        work = SyncWork.Applying; progress = FavoriteSyncProgress(FavoriteSyncPhase.ReadWeb)
        scope.launch {
            var processed = 0
            var queued = 0
            try {
                onApply(reviewed, { progress = it }) { count, pending ->
                    processed = count; queued = pending
                    progress = FavoriteSyncProgress(FavoriteSyncPhase.Write, count, reviewed.changes.size)
                }
                onFinished(if(queued == 0) "收藏同步完成：新增 ${reviewed.additions} 本，移动 ${reviewed.moves} 本"
                    else "已同步 ${processed - queued} 本，另有 $queued 本已加入待同步队列")
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) {
                val partial = if(processed == 0) "" else "已同步 ${processed - queued} 本，待同步 $queued 本。"
                error = "同步已停止。$partial${failure.friendlyMessage()}"; plan = null
            } finally { work = null }
        }
    }

    val reviewed = plan
    val preview = work == null && reviewed != null && reviewed.changes.isNotEmpty()
    val direction = if(priority == FavoriteSyncPriority.Local) "本地 → 云端" else "云端 → 本地"
    AppSheet(onDismissRequest = { if(!applying) onDismiss() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth()) {
            FavoriteSyncHeader(when {
                applying -> "正在同步"
                work == SyncWork.Checking -> "检查收藏差异"
                reviewed != null -> "同步预览"
                else -> "收藏同步"
            }, "$direction · $account", if(applying) null else onDismiss)
            if(preview) FavoriteSyncPreview(reviewed, Modifier.weight(1f, fill = false))
            else AppScrollColumn(modifier = Modifier.weight(1f, fill = false),
                contentModifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    work != null -> FavoriteSyncProgressCard(progress, applying)
                    reviewed != null -> FavoriteSyncEmpty()
                    else -> FavoriteSyncSetup(priority, error) { priority = it; error = null }
                }
            }
            if(!applying) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if(work == SyncWork.Checking) AppOutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消检查") }
                else if(reviewed == null) AppButton(onClick = ::check, modifier = Modifier.weight(1f)) { Text("检查差异") }
                else {
                    AppOutlinedButton(onClick = { plan = null }, modifier = Modifier.weight(1f)) { Text("更换基准") }
                    if(reviewed.changes.isEmpty()) AppButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("完成") }
                    else AppButton(onClick = { apply(reviewed) }, modifier = Modifier.weight(1f)) { Text("确认同步") }
                }
            }
        }
    }
}
