package cc.novelia.app.reader

internal data class ReadingTimeSlice(val startedAt: Long, val millis: Long)

/** 墙上时钟只给记录标日期；暂停/恢复、重建与重复 flush 均不会重复累计。 */
internal class ReadingSessionClock(private val idleMillis: Long = 300_000) {
    private var lastSample: Long? = null
    private var lastInteraction = 0L
    private var active = false
    private var automatic = false

    fun update(now: Long, wallNow: Long, active: Boolean, automatic: Boolean = false, interacted: Boolean = false): ReadingTimeSlice? {
        val previous = lastSample ?: now
        val end = if(this.automatic) now else minOf(now, lastInteraction + idleMillis)
        val duration = if(this.active) (end - previous).coerceAtLeast(0) else 0
        val slice = if(duration > 0) ReadingTimeSlice(wallNow - (now - end) - duration, duration) else null
        if(interacted || (!this.active && active)) lastInteraction = now
        this.active = active
        this.automatic = automatic
        lastSample = now
        return slice
    }
}
