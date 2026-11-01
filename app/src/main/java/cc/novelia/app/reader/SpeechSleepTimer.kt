package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderSettings

/** 使用单调时钟。只在开始或用户重设时更新，暂停、续章和重试共用原期限。 */
internal class SpeechSleepTimer {
    private var deadline: Long? = null

    /** 返回需要调度的延迟；null 表示不限时，调用方必须撤销此前的回调。 */
    fun reset(minutes: Int, now: Long): Long? {
        val resolved = ReaderSettings(speechMinutes = minutes).resolvedSpeechMinutes
        val duration = resolved.takeIf { it > 0 }?.let { it * 60_000L }
        deadline = duration?.let { now + it }
        return duration
    }

    fun expired(now: Long): Boolean = deadline?.let { now >= it } ?: false
}
