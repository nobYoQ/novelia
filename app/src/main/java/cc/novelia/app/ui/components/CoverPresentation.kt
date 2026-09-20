package cc.novelia.app.ui.components


internal fun stableCoverVariant(key: String, count: Int): Int = (key.hashCode() and Int.MAX_VALUE) % count
