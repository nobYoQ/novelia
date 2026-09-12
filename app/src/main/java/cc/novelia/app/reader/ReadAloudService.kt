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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cc.novelia.app.MainActivity
import cc.novelia.app.R
import cc.novelia.app.data.ReaderSettings
import cc.novelia.app.data.appJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Locale

class ReadAloudService : Service() {
    private var engine: TextToSpeech? = null
    private var paragraphs = emptyList<String>(); private var index = 0; private var ready = false; private var paused = false; private var title = "朗读"; private var rate = 1f; private var language = Locale.SIMPLIFIED_CHINESE
    private val handler = Handler(Looper.getMainLooper())
    private val stopTimer = Runnable { paused = true; status.value = SLEEP_TIMER_FINISHED; stopSelf() }
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
            override fun onStart(utteranceId: String?) { if(!paused) status.value = "正在朗读：$title" }
            override fun onDone(utteranceId: String?) { handler.post { if(!paused) { index++; speak() } } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { status.value = "朗读失败，请检查系统语音包"; stopSelf() }
        })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            "stop" -> { status.value = "朗读已停止"; stopSelf() }
            "pause" -> pause()
            "resume" -> { paused = false; configureAndSpeak() }
            "start" -> {
                paragraphs = runCatching { appJson.decodeFromString<List<String>>(File(cacheDir, "tts-queue.json").readText()) }.getOrDefault(emptyList()).flatMap { it.chunked(3500) }
                index = 0; title = intent.getStringExtra("title") ?: "小说朗读"; rate = intent.getFloatExtra("rate", 1f); language = if(intent.getBooleanExtra("japanese", false)) Locale.JAPAN else Locale.SIMPLIFIED_CHINESE; paused = false
                startForeground(100, notification()); handler.removeCallbacks(stopTimer); handler.postDelayed(stopTimer, intent.getIntExtra("minutes", 30) * 60000L)
                if(ready) configureAndSpeak()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun configureAndSpeak() {
        if(!ready || paragraphs.isEmpty()) return
        val result = engine?.setLanguage(language)
        if(result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) { status.value = "缺少${if(language == Locale.JAPAN) "日文" else "中文"}语音包，请在系统文字转语音设置中安装"; stopSelf(); return }
        if(getSystemService(AudioManager::class.java).requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { pause(); return }
        engine?.setSpeechRate(rate); speak(); getSystemService(NotificationManager::class.java).notify(100, notification())
    }
    private fun speak() { if(index >= paragraphs.size) { status.value = "本章朗读完成"; stopSelf() } else if(!paused) engine?.speak(paragraphs[index], TextToSpeech.QUEUE_FLUSH, null, "$index") }
    private fun pause() { paused = true; engine?.stop(); status.value = "朗读已暂停"; getSystemService(NotificationManager::class.java).notify(100, notification()) }
    private fun notification(): Notification {
        fun pending(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, ReadAloudService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "reading").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(if(paused) "朗读已暂停" else "正在朗读").setOngoing(!paused)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, if(paused) "继续" else "暂停", pending(if(paused) "resume" else "pause")).addAction(0, "停止", pending("stop")).build()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); engine?.stop(); engine?.shutdown(); getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val SLEEP_TIMER_FINISHED = "朗读定时已结束"
        val status = MutableStateFlow("")
        fun start(context: Context, paragraphs: List<String>, title: String, settings: ReaderSettings) {
            File(context.cacheDir, "tts-queue.json").writeText(appJson.encodeToString(paragraphs))
            status.value = "正在准备朗读…"
            ContextCompat.startForegroundService(context, Intent(context, ReadAloudService::class.java).setAction("start").putExtra("title", title).putExtra("rate", settings.speechRate).putExtra("minutes", settings.speechMinutes).putExtra("japanese", settings.speechLanguage == "jp" || (settings.speechLanguage == "auto" && settings.mode.startsWith("jp"))))
        }
    }
}
