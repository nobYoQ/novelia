package cc.novelia.app.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.novelia.app.reader.ReadingTextMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ReaderChapterTarget(
    val id: String,
    val startAtEnd: Boolean = false,
    val searchMatch: ReadingTextMatch? = null
)

/** The displayed chapter remains mounted until this request has succeeded. */
internal class ReaderChapterLoad<T>(
    private val scope: CoroutineScope,
    private val load: suspend (String) -> T,
    private val onLoaded: (ReaderChapterTarget, T) -> Unit,
    private val errorMessage: (Exception) -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) {
    var target by mutableStateOf<ReaderChapterTarget?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null
    private var generation = 0

    fun request(next: ReaderChapterTarget) {
        if(loading && target == next) return
        cancel()
        target = next
        loading = true
        val requestGeneration = generation
        job = scope.launch(dispatcher) {
            try {
                val result = load(next.id)
                ensureActive()
                if(generation == requestGeneration) onLoaded(next, result)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(generation == requestGeneration) error = errorMessage(e)
            } finally {
                if(generation == requestGeneration) {
                    loading = false
                    if(error == null) target = null
                }
            }
        }
    }

    fun retry() { target?.let(::request) }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        target = null
        loading = false
        error = null
    }
}
