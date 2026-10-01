package cc.novelia.app.reader

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.R
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.storage.appJson
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

/**
 * 系统 TTS 前台服务，持有句子队列、音频焦点、暂停状态和定时停止任务。
 * 队列先写入缓存文件，Intent 只传 UUID，避免大章节超过 Binder 事务大小限制。
 * TTS 回调转到主线程后校验 utterance ID，旧播放/暂停产生的迟到回调不能推进新队列。
 */
class ReadAloudService : Service() {
    private var engine: TextToSpeech? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var continuation: SpeechChapterSequence? = null
    private var binding: SessionBinding? = null
    private var stopAt = Long.MAX_VALUE
    private var loading = false
    private var activeRequest = ""
    private var currentUtterance: String? = null
    private var utteranceSequence = 0L
    private var paragraphs = emptyList<String>(); private var index = 0; private var ready = false; private var paused = false; private var title = "朗读"; private var rate = 1f; private var language = Locale.SIMPLIFIED_CHINESE
    private val handler = Handler(Looper.getMainLooper())
    private val stopTimer = Runnable { stopPlayback(SLEEP_TIMER_FINISHED) }
    private lateinit var focus: AudioFocusRequest
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("reading", "小说朗读", NotificationManager.IMPORTANCE_LOW))
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener { if(it <= AudioManager.AUDIOFOCUS_LOSS) pause() }.build()
        engine = TextToSpeech(this) { result -> handler.post {
            ready = result == TextToSpeech.SUCCESS
            if(ready) configureAndSpeak() else { status.value = "系统朗读引擎不可用，请在系统设置中安装语音"; stopSelf() }
        } }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { handler.post { if(!paused && isCurrentUtterance(utteranceId)) status.value = "正在朗读：$title" } }
            override fun onDone(utteranceId: String?) { handler.post { if(!paused && isCurrentUtterance(utteranceId)) { index++; speak() } } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { handler.post { if(isCurrentUtterance(utteranceId)) { status.value = "朗读失败，请检查系统语音包"; stopSelf() } } }
        })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            "stop" -> { requestGeneration.incrementAndGet(); stopPlayback("朗读已停止") }
            "pause" -> pause()
            "resume" -> {
                paused = false
                if(loading) {
                    status.value = if(continuation != null) "正在准备下一章…" else "正在准备朗读…"
                    getSystemService(NotificationManager::class.java).notify(100, notification())
                } else configureAndSpeak()
            }
            "start" -> {
                loadJob?.cancel(); continuation = null; binding = null; currentUtterance = null; engine?.stop(); paragraphs = emptyList(); loading = true
                index = 0; title = intent.getStringExtra("title") ?: "小说朗读"; rate = intent.getFloatExtra("rate", 1f); language = if(intent.getBooleanExtra("japanese", false)) Locale.JAPAN else Locale.SIMPLIFIED_CHINESE; paused = false
                // 按 Android 要求立即进入前台服务，再把全部 IO 放到主线程之外。
                startForeground(100, notification()); handler.removeCallbacks(stopTimer)
                val duration = intent.getIntExtra("minutes", 30).coerceIn(1, 180) * 60000L
                stopAt = SystemClock.elapsedRealtime() + duration
                handler.postDelayed(stopTimer, duration)
                val requestId = intent.getStringExtra("queue")
                if(requestId == null || !QUEUE_ID.matches(requestId)) { status.value = "朗读内容不可用，请重新开始"; stopSelf(); return START_NOT_STICKY }
                activeRequest = requestId
                loadJob = serviceScope.launch {
                    try {
                        val request = withContext(Dispatchers.IO) {
                            val file = queueFile(applicationContext, requestId)
                            try { appJson.decodeFromString<SpeechRequest>(file.readText(Charsets.UTF_8)) }
                            finally { file.delete() }
                        }
                        binding = request.ref?.takeUnless { it.isLocal }?.let { SessionBinding(request.account, request.sessionGeneration) }
                        continuation = request.ref?.takeIf { request.settings.speechContinueChapters }?.let { ref ->
                            SpeechChapterSequence(request.chapterId, request.nextId, request.settings) { id -> loadChapter(ref, id, request) }
                        }
                        paragraphs = request.paragraphs; loading = false
                        configureAndSpeak()
                    } catch(e: CancellationException) { throw e }
                    catch(_: Exception) { status.value = "朗读内容不可用，请重新开始"; stopSelf() }
                    finally { withContext(NonCancellable + Dispatchers.IO) { queueFile(applicationContext, requestId).delete() } }
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun configureAndSpeak() {
        if(!ready || loading || paused || activeRequest.isEmpty()) return
        if(SystemClock.elapsedRealtime() >= stopAt) { stopPlayback(SLEEP_TIMER_FINISHED); return }
        val result = engine?.setLanguage(language)
        if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) { status.value = "缺少${if(language == Locale.JAPAN) "日文" else "中文"}语音包，请在系统文字转语音设置中安装"; stopSelf(); return }
        if(getSystemService(AudioManager::class.java).requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { pause(); return }
        engine?.setSpeechRate(rate); speak(); getSystemService(NotificationManager::class.java).notify(100, notification())
    }
    private fun isCurrentUtterance(id: String?) = id != null && id == currentUtterance && !loading && !paused
    private fun speak() {
        if(SystemClock.elapsedRealtime() >= stopAt) { stopPlayback(SLEEP_TIMER_FINISHED); return }
        if(binding?.let { it != (application as NoveliaApplication).session.capture() } == true) {
            stopPlayback("登录账号已变化，请重新开始朗读"); return
        }
        if(index >= paragraphs.size) continueChapter()
        else if(!paused) {
            val id = "$activeRequest:$index:${++utteranceSequence}"
            currentUtterance = id
            engine?.speak(paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }
    private suspend fun loadChapter(ref: BookRef, id: String, request: SpeechRequest): Chapter {
        val app = application as NoveliaApplication
        app.initialization.await()
        val binding = SessionBinding(request.account, request.sessionGeneration)
        if(!ref.isLocal) app.session.ensureCurrent(binding)
        val loaded = resolveSpeechChapter(ref, id, request.settings.speechNetworkContinuation, local = { chapterId ->
            val document = app.store.documentIndex(ref.id)
            val chapterIndex = document.chapters.indexOfFirst { it.id == chapterId }
            check(chapterIndex >= 0) { "下一章已不存在，请从目录重新开始朗读" }
            val chapter = app.store.documentChapter(ref.id, chapterId)
            Chapter(chapter.title, chapter.title, document.name, document.name,
                document.chapters.getOrNull(chapterIndex - 1)?.id, document.chapters.getOrNull(chapterIndex + 1)?.id,
                chapter.paragraphs, chapter.paragraphs)
        }, cached = { app.store.cachedChapter(ref, it) }, network = {
            app.store.chapterRequests.load(app.api, app.session, binding, app.store.cacheGeneration.value, ref, it)
        })
        if(!ref.isLocal) app.session.ensureCurrent(binding)
        return loaded
    }
    private fun continueChapter() {
        if(loading || paused) return
        val sequence = continuation ?: run { stopPlayback("本章朗读完成"); return }
        currentUtterance = null; loading = true
        status.value = "正在准备下一章…"
        getSystemService(NotificationManager::class.java).notify(100, notification())
        val requestId = activeRequest
        loadJob = serviceScope.launch {
            try {
                val chapter = withContext(Dispatchers.IO) { sequence.next() }
                ensureActive()
                if(activeRequest != requestId) return@launch
                if(SystemClock.elapsedRealtime() >= stopAt) { stopPlayback(SLEEP_TIMER_FINISHED); return@launch }
                if(chapter == null) { stopPlayback("朗读完成，已到末章"); return@launch }
                paragraphs = chapter.paragraphs; index = 0; title = chapter.title; loading = false
                getSystemService(NotificationManager::class.java).notify(100, notification())
                configureAndSpeak()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(activeRequest == requestId) {
                    loading = false; paused = true
                    val message = if(e is IllegalStateException) e.message.orEmpty() else "章节加载失败，请检查网络或登录状态"
                    status.value = "续章已暂停：$message。点击继续可重试"
                    getSystemService(NotificationManager::class.java).notify(100, notification())
                }
            }
        }
    }
    private fun stopPlayback(message: String) {
        handler.removeCallbacks(stopTimer)
        paused = true; activeRequest = ""; currentUtterance = null; loadJob?.cancel(); engine?.stop()
        status.value = message
        stopSelf()
    }
    private fun pause() { paused = true; currentUtterance = null; engine?.stop(); status.value = "朗读已暂停"; getSystemService(NotificationManager::class.java).notify(100, notification()) }
    private fun notification(): Notification {
        fun pending(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "reading").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(if(paused) "朗读已暂停" else if(loading) "正在准备朗读…" else "正在朗读").setOngoing(!paused)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, if(paused) "继续" else "暂停", pending(if(paused) "resume" else "pause")).addAction(0, "停止", pending("stop")).build()
    }
    override fun onDestroy() { serviceScope.cancel(); handler.removeCallbacksAndMessages(null); engine?.stop(); engine?.shutdown(); getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val SLEEP_TIMER_FINISHED = "朗读定时已结束"
        const val EMPTY_QUEUE = "当前内容没有可朗读的文字"
        val status = MutableStateFlow("")
        private val requestGeneration = AtomicLong()
        private val QUEUE_ID = Regex("[a-f0-9-]{36}")
        private fun queueFile(context: Context, id: String) = File(context.cacheDir, "tts-queue-$id.json")
        /**
         * 后台准备可取消的朗读队列；generation 保证较早的准备任务不会覆盖后来的开始/停止操作。
         * 成功交给服务后由服务删除队列文件，交接前失败则由此处回收。
         */
        suspend fun start(context: Context, title: String, settings: ReaderSettings,
            ref: BookRef? = null, chapterId: String? = null, nextId: String? = null, content: () -> List<String>) {
            val generation = requestGeneration.incrementAndGet()
            val appContext = context.applicationContext
            val id = UUID.randomUUID().toString()
            val file = queueFile(appContext, id)
            val binding = (appContext as? NoveliaApplication)?.session?.capture()
            var submitted = false
            status.value = "正在准备朗读…"
            try {
                val hasText = withContext(Dispatchers.IO) {
                    val jobContext = currentCoroutineContext()
                    val queue = prepareSpeechQueue(content()) { jobContext.ensureActive() }
                    if(queue.isEmpty() && !(settings.speechContinueChapters && ref != null && nextId != null)) false
                    else {
                        file.writeText(appJson.encodeToString(SpeechRequest(queue, settings, ref, chapterId, nextId, binding?.account, binding?.generation ?: 0)), Charsets.UTF_8)
                        true
                    }
                }
                if(requestGeneration.get() != generation) return
                if(!hasText) {
                    status.value = EMPTY_QUEUE
                    appContext.stopService(Intent(appContext, ReadAloudService::class.java))
                    return
                }
                ContextCompat.startForegroundService(appContext, Intent(appContext, ReadAloudService::class.java).setAction("start").putExtra("queue", id).putExtra("title", title).putExtra("rate", settings.speechRate).putExtra("minutes", settings.speechMinutes).putExtra("japanese", settings.speechLanguage == "jp" || (settings.speechLanguage == "auto" && settings.mode.startsWith("jp"))))
                submitted = true
            } catch(e: CancellationException) {
                if(requestGeneration.get() == generation) status.value = "朗读准备已取消"
                throw e
            } catch(e: Exception) {
                if(requestGeneration.get() == generation) status.value = "朗读准备失败，请重试"
                throw e
            } finally {
                if(!submitted) withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }
    }
}
