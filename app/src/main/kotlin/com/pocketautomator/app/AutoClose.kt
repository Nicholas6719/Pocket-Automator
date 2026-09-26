package com.pocketautomator.app

/**
 * Closes emulators you've left behind, kept apart from Android so the rules
 * can be checked without a device.
 *
 * An emulator is *left behind* once something else comes to the front. It is
 * closed [delayMs] after either of two things:
 * - the screen turns off (the one in front then is never closed: sleeping
 *   mid-game keeps the game), or
 * - a different emulator opens.
 * Coming back to it before then keeps it open. Only emulators whose profile
 * allows it are ever closed ([closable]).
 */
class AutoClose(
    private val delayMs: () -> Long,
    /** A linked app: an emulator, which also counts as "another game" opening. */
    private val isEmulator: (String) -> Boolean,
    /** Whether this emulator's profile lets it be closed. */
    private val closable: (String) -> Boolean,
) {
    private var front: String? = null
    private val left = linkedSetOf<String>()

    /** Package → when it closes. */
    private val pending = linkedMapOf<String, Long>()

    val waiting: Map<String, Long> get() = pending.toMap()
    val leftBehind: Set<String> get() = left.toSet()

    fun onFront(pkg: String, now: Long) {
        val before = front
        front = pkg
        if (before != null && before != pkg && isEmulator(before)) left += before
        left -= pkg
        pending -= pkg
        if (isEmulator(pkg) && before != pkg) schedule(now)
    }

    fun onScreenOff(now: Long) = schedule(now)

    private fun schedule(now: Long) {
        val at = now + delayMs()
        for (pkg in left) {
            if (pkg == front || !closable(pkg)) continue
            pending.putIfAbsent(pkg, at)
        }
    }

    /** The emulators due to close by [now]; they are forgotten once returned. */
    fun due(now: Long): List<String> {
        val ready = pending.filterValues { it <= now }.keys.toList()
        ready.forEach { pending -= it; left -= it }
        return ready
    }

    /** When the next close is due, if any. */
    fun next(): Long? = pending.values.minOrNull()

    fun reset() {
        front = null
        left.clear()
        pending.clear()
    }
}
