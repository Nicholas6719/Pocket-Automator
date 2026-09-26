package com.pocketautomator.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import java.io.File
import java.util.concurrent.Executors

/**
 * Puts profiles into effect: told which app is in front, it works out the
 * profile, reads what the device has now, and writes what [Plan] says, all
 * through Shizuku on a thread of its own so switches never overlap.
 */
class Automator(private val context: Context) {

    private val store = Store.get(context)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val overlay = Overlay(context)

    /** Every root task, as last reported: which Recents cards an app has (main thread). */
    var tasks: List<TaskEntry> = emptyList()
        set(value) {
            field = value
            val open = value.mapNotNull { it.pkg }.toSet()
            // A game ends with its emulator's last card.
            games.keys.retainAll(open)
            // Azahar closed: its own resolution goes back, if a game's was swapped in.
            val azahar = Emulator.AZAHAR.pkg in open
            if (azaharOpen != false && !azahar) GameSettings.restoreAzahar(context)
            azaharOpen = azahar
        }

    /** Whether Azahar had a card last time; null before the first report, so a leftover swap is put back at start. */
    private var azaharOpen: Boolean? = null

    /** The game ES-DE started in each emulator still open (main thread). */
    private val games = mutableMapOf<String, GameEvent>()
    private var lastEvent = 0L
    private var esde: File? = null
    private var esdeChecked = 0L

    private val closer = AutoClose(
        delayMs = { store.autoCloseDelay.value * 1000L },
        isEmulator = { pkg -> store.profiles.value.any { !it.isDefault && pkg in it.apps } },
        tracked = { pkg -> pkg in store.autoCloseApps.value || store.profiles.value.any { !it.isDefault && pkg in it.apps } },
        closable = ::canClose,
        open = { tasks.mapNotNull { it.pkg } },
        closeOnHome = { store.closeOnHome.value },
    )
    private val closeDue = Runnable { closeDue() }
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAutomator:autoClose")
        .apply { setReferenceCounted(false) }

    /** Whether [pkg] is one of the apps auto-close may close. */
    private fun canClose(pkg: String): Boolean =
        store.autoClose.value && (
            pkg in store.autoCloseApps.value ||
                Plan.profileFor(store.profiles.value, pkg).let { !it.isDefault && it.autoClose }
            )

    /** The app in front and the profile last put into effect (main thread). */
    private var app: String? = null
    private var appliedKey: String? = null
    private var appliedSettings: Map<Knob, Int>? = null

    /** The app in front changed to [task] (main thread). */
    fun onApp(task: TaskEntry) {
        val pkg = task.pkg ?: return
        if (pkg == app) return
        val previous = app
        app = pkg
        closer.onFront(pkg, SystemClock.elapsedRealtime(), home = task.type == TaskList.TYPE_HOME || pkg in FRONTENDS)
        previous?.let(::watchForQuit)
        scheduleClose()
        if (task.type != TaskList.TYPE_HOME && pkg != context.packageName) detectGame(pkg)
        val (key, profile) = effective(pkg)
        store.setNow { it.copy(app = pkg, game = games[pkg]) }
        val arriving = !profile.isDefault
        apply(key, profile, announce = key != appliedKey, reason = pkg)
        if (arriving) profile.settings[Knob.GAME_SCREEN]?.let { place(task, it) }
    }

    /**
     * An app just came to the front: if ES-DE started a game in the last
     * half minute (its hook wrote it down), that's the game in it.
     */
    private fun detectGame(pkg: String) {
        if (!store.gameDetection.value) return
        val now = System.currentTimeMillis()
        if (esde == null && now - esdeChecked > 60_000) {
            esdeChecked = now
            esde = EsDe.home()
        }
        val event = esde?.let { Hooks.readEvent(it) } ?: return
        if (event.time <= lastEvent || now / 1000 - event.time > 30) return
        lastEvent = event.time
        games[pkg] = event
        store.log("game: ${event.name} (${event.system}) in $pkg")
        if (pkg == Emulator.EDEN.pkg) main.postDelayed({ GameSettings.learnEdenId(context, event) }, 20_000)
    }

    /**
     * The profile for [pkg], with the settings of the game in it on top, and
     * a key that tells them apart: "p<id>" or "g<game id>".
     */
    private fun effective(pkg: String?): Pair<String, Profile> {
        val profile = Plan.profileFor(store.profiles.value, pkg)
        val game = pkg?.let { games[it] }?.let { store.game(it.id) }?.takeIf { it.settings.isNotEmpty() }
            ?: return "p${profile.id}" to profile
        // A game with settings of its own counts as a game even under Default.
        return "g${game.id}" to profile.copy(
            id = if (profile.isDefault) GAME_ID else profile.id,
            name = game.name,
            settings = profile.settings + game.settings,
        )
    }

    /** Profiles or games' settings were edited: put the one in use back into effect if anything about it changed. */
    fun onProfilesChanged() {
        if (!store.enabled.value) return
        val (key, profile) = effective(app)
        val changed = key != appliedKey
        if (changed || profile.settings != appliedSettings) {
            apply(key, profile, announce = changed && app != null, reason = "profiles edited")
        }
    }

    /**
     * [pkg] was just left. If it quit by itself (you quit the game), its
     * leftover Recents card is cleared within a few seconds: nothing is
     * running, so there's nothing to lose. One still running closes by the
     * usual rules (screen off, or another emulator).
     */
    private fun watchForQuit(pkg: String) {
        if (pkg == context.packageName || !canClose(pkg)) return
        for (wait in longArrayOf(2_000, 5_000, 9_000)) main.postDelayed({ clearIfQuit(pkg) }, wait)
    }

    private fun clearIfQuit(pkg: String) {
        if (pkg == app) return
        worker.execute {
            if (!Shell.ready || pkg == app) return@execute
            val cleared = Shell.sh(Commands.CLEAR_IF_QUIT, pkg).out.lines().filter { it.isNotBlank() }
            if (cleared.isNotEmpty()) store.log("cleared $pkg from Recents (it had quit)")
        }
    }

    /** The screen went off: emulators left behind close after the delay; the one in front stays (main thread). */
    fun onScreenOff() {
        closer.onScreenOff(SystemClock.elapsedRealtime())
        scheduleClose()
    }

    /** Emulators waiting to close, and when (elapsed-realtime ms), for the Background page. */
    fun waitingToClose(): Map<String, Long> = closer.waiting

    private fun scheduleClose() {
        main.removeCallbacks(closeDue)
        val next = closer.next() ?: run { if (wakeLock.isHeld) wakeLock.release(); return }
        val wait = (next - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        // The screen may be off: without this the CPU sleeps and the close waits for the next wake.
        wakeLock.acquire(wait + 3_000)
        main.postDelayed(closeDue, wait)
    }

    private fun closeDue() {
        val due = closer.due(SystemClock.elapsedRealtime())
        for (pkg in due) {
            val cards = tasks.filter { it.pkg == pkg && it.id >= 0 }.map { it.id }
            worker.execute {
                if (!Shell.ready) {
                    store.log("couldn't close $pkg: Shizuku isn't running")
                    return@execute
                }
                val result = Shell.sh(Commands.close(pkg, cards))
                store.log("closed $pkg (left behind)" + if (result.ok) "" else ": ${result.err.trim().take(80)}")
            }
        }
        scheduleClose()
    }

    /** Nothing has been put into effect yet (this app itself is in front): Default applies, quietly. */
    fun ensureApplied() {
        if (appliedKey == null) store.default().let { apply("p${it.id}", it, announce = false, reason = "started") }
    }

    /** Nothing is being watched any more (Shizuku stopped, or automation turned off). */
    fun forget() {
        app = null
        appliedKey = null
        appliedSettings = null
        store.setNow { it.copy(app = null, profileId = null, game = null) }
    }

    /** Puts [profile] into effect right away, as the editor's Apply button does. */
    fun applyNow(profile: Profile) = apply("p${profile.id}", profile, announce = true, reason = "applied by hand")

    private fun apply(key: String, profile: Profile, announce: Boolean, reason: String) {
        appliedKey = key
        appliedSettings = profile.settings
        store.setNow { it.copy(profileId = profile.id) }
        // Said as the game opens rather than once the writes are done: the fan's pause makes those take a second.
        if (announce && store.messages && Shell.ready) overlay.show(message(profile))
        val base = store.default().settings
        worker.execute {
            if (!Shell.ready) {
                store.log("${profile.name}: skipped, Shizuku isn't running ($reason)")
                return@execute
            }
            val before = read()
            val step = Plan.switchTo(profile.settings, base, before?.values.orEmpty(), store.snapshot)
            if (step.writes.isNotEmpty()) {
                val result = runCatching { Shell.sh(Commands.script(step.writes)) }.getOrNull()
                if (result == null || !result.ok) {
                    store.log("${profile.name}: some changes failed: ${result?.err?.trim().orEmpty().take(120)}")
                }
            }
            store.snapshot = step.snapshot
            val changes = step.writes.entries.joinToString { (k, v) -> "${Device.title(k)} ${Device.describe(k, v)}" }
            store.log("${profile.name} ← $reason" + if (changes.isEmpty()) " (nothing to change)" else ": $changes")
            refreshReadings()
        }
    }

    /** Reads the device now and publishes it for the status card. Blocking. */
    fun refreshReadings(): Commands.Readings? {
        val readings = read() ?: return null
        store.setNow { it.copy(readings = readings) }
        return readings
    }

    fun refreshReadingsAsync() = worker.execute { refreshReadings() }

    private fun read(): Commands.Readings? =
        runCatching { Shell.sh(Commands.readScript) }.getOrNull()?.takeIf { it.out.isNotBlank() }?.let { Commands.parse(it.out) }

    /** Moves the game to the screen its profile asks for, once, as it arrives. */
    private fun place(task: TaskEntry, screen: Int) {
        val target = when (screen) {
            Knob.SCREEN_TOP -> Display.DEFAULT_DISPLAY
            else -> otherDisplay() ?: return
        }
        if (task.display == target || task.id < 0) return
        worker.execute {
            if (!Shell.ready) return@execute
            val result = Shell.sh(Commands.moveTask(task.id, target))
            store.log("moved ${task.pkg} to display $target" + if (result.ok) "" else " (failed: ${result.err.trim().take(80)})")
        }
    }

    private fun otherDisplay(): Int? =
        context.getSystemService(DisplayManager::class.java).displays
            .map { it.displayId }.filter { it != Display.DEFAULT_DISPLAY }.minOrNull()

    fun release() = main.post {
        overlay.remove()
        main.removeCallbacks(closeDue)
        if (wakeLock.isHeld) wakeLock.release()
    }

    companion object {
        /** Frontends that count as home even when they aren't the launcher (ES-DE on the Odin 2 Portal). */
        val FRONTENDS = setOf(Hooks.ESDE_PACKAGE)

        /** What the status card shows as in use while a game's own settings apply under Default. */
        const val GAME_ID = -1

        /** "Heavy games · High Performance · Smart fan". */
        fun message(profile: Profile): String {
            val parts = mutableListOf(profile.name)
            profile.settings[Knob.PERFORMANCE]?.let { parts += Device.describe(Knob.PERFORMANCE, it) }
            profile.settings[Knob.FAN]?.let { parts += "${Device.describe(Knob.FAN, it)} fan" }
            return parts.joinToString(" · ")
        }
    }
}
