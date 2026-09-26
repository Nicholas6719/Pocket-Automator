package com.pocketautomator.app

/**
 * Closes emulators (and other apps you pick) you've left behind, kept apart
 * from Android so the rules can be checked without a device.
 *
 * A tracked app is *left behind* once something else comes to the front. It is
 * closed [delayMs] after any of these:
 * - you're back on the home screen (ES-DE), when [closeOnHome] is on,
 * - the screen turns off (the one in front then is never closed: sleeping
 *   mid-game keeps the game), or
 * - a different emulator opens.
 * Coming back to it before then keeps it open. Only emulators whose profile
 * allows it are ever closed ([closable]).
 *
 * An app with a Recents card ([open]) that isn't in front counts as left
 * behind too, even if this didn't see it leave: after Pocket Automator
 * restarts (an update, Shizuku coming back), games already in the
 * background still close.
 */
class AutoClose(
    private val delayMs: () -> Long,
    /** A linked app: an emulator, which also counts as "another game" opening. */
    private val isEmulator: (String) -> Boolean,
    /** Any app that may be closed once left: emulators, and the extra apps picked for auto-close. */
    private val tracked: (String) -> Boolean = isEmulator,
    /** Whether this emulator's profile lets it be closed. */
    private val closable: (String) -> Boolean,
    /** The apps that have a Recents card now. */
    private val open: () -> Collection<String> = { emptyList() },
    /** Whether being back on the home screen starts the countdown too. */
    private val closeOnHome: () -> Boolean = { false },
) {
    private var front: String? = null
    private val left = linkedSetOf<String>()

    /** Package → when it closes. */
    private val pending = linkedMapOf<String, Long>()

    val waiting: Map<String, Long> get() = pending.toMap()
    val leftBehind: Set<String> get() = left.toSet()

    /** [pkg] came to the front; [home] when it's the home screen (ES-DE). */
    fun onFront(pkg: String, now: Long, home: Boolean = false) {
        val before = front
        front = pkg
        if (before != null && before != pkg && tracked(before)) left += before
        left -= pkg
        pending -= pkg
        if (before != pkg && (isEmulator(pkg) || (home && closeOnHome()))) schedule(now)
    }

    fun onScreenOff(now: Long) = schedule(now)

    private fun schedule(now: Long) {
        // Until the app in front is known, nothing counts as left behind.
        val front = front ?: return
        left += open().filter { it != front && tracked(it) }
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
