package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderSettings

/** 按实际经过时间计算位移；掉帧或恢复后最多补偿 100 ms，避免一跃跳过正文。 */
internal fun autoReadDistance(settings: ReaderSettings, density: Float, elapsedMillis: Long): Float =
    settings.resolvedAutoReadSpeed * density.coerceAtLeast(0f) * elapsedMillis.coerceIn(0, 100) / 1000f
