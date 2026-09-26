package com.pocketautomator.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
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

    private val closer = AutoClose(
        delayMs = { store.autoCloseDelay.value * 1000L },
        isEmulator = { pkg -> store.profiles.value.any { !it.isDefault && pkg in it.apps } },
        closable = { pkg ->
            store.autoClose.value && Plan.profileFor(store.profiles.value, pkg).let { !it.isDefault && it.autoClose }
        },
    )
    private val closeDue = Runnable { closeDue() }
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAutomator:autoClose")
        .apply { setReferenceCounted(false) }

    /** The app in front and the profile last put into effect (main thread). */
    private var app: String? = null
    private var appliedId: Int? = null
    private var appliedSettings: Map<Knob, Int>? = null

    /** The app in front changed to [task] (main thread). */
    fun onApp(task: TaskEntry) {
        val pkg = task.pkg ?: return
        if (pkg == app) return
        app = pkg
        closer.onFront(pkg, SystemClock.elapsedRealtime())
        scheduleClose()
        store.setNow { it.copy(app = pkg) }
        val profile = Plan.profileFor(store.profiles.value, pkg)
        val arriving = !profile.isDefault
        apply(profile, announce = profile.id != appliedId, reason = pkg)
        if (arriving) profile.settings[Knob.GAME_SCREEN]?.let { place(task, it) }
    }

    /** Profiles were edited: put the one in use back into effect if anything about it changed. */
    fun onProfilesChanged() {
        if (!store.enabled.value) return
        val profile = Plan.profileFor(store.profiles.value, app)
        val changed = profile.id != appliedId
        if (changed || profile.settings != appliedSettings) {
            apply(profile, announce = changed && app != null, reason = "profiles edited")
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
        if (appliedId == null) apply(store.default(), announce = false, reason = "started")
    }

    /** Nothing is being watched any more (Shizuku stopped, or automation turned off). */
    fun forget() {
        app = null
        appliedId = null
        appliedSettings = null
        store.setNow { it.copy(app = null, profileId = null) }
    }

    /** Puts [profile] into effect right away, as the editor's Apply button does. */
    fun applyNow(profile: Profile) = apply(profile, announce = true, reason = "applied by hand")

    private fun apply(profile: Profile, announce: Boolean, reason: String) {
        appliedId = profile.id
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
        /** "Heavy games · High Performance · Smart fan". */
        fun message(profile: Profile): String {
            val parts = mutableListOf(profile.name)
            profile.settings[Knob.PERFORMANCE]?.let { parts += Device.describe(Knob.PERFORMANCE, it) }
            profile.settings[Knob.FAN]?.let { parts += "${Device.describe(Knob.FAN, it)} fan" }
            return parts.joinToString(" · ")
        }
    }
}
