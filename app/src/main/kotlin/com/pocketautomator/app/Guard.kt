package com.pocketautomator.app

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlin.system.exitProcess

/**
 * The helper that starts Pocket Automator again after something stops it: a
 * swipe in Recents that force-stops apps (the Odin 2 Portal's launcher does),
 * or a force stop in Settings. Shizuku runs it as the shell user (or root, if
 * Shizuku was started as root) and keeps it running after Pocket Automator's
 * own process is gone (a "daemon" user service), so it works where the root
 * watchdog can't start.
 *
 * Every [CHECK_MS] it looks for the app's process and, when there is none,
 * starts its service the way `adb shell` would. It does the same for the
 * keep-alive list ([keepApps]), as the root watchdog does. [GuardLink] starts
 * it while keep-alive is on and stops it when keep-alive is turned off.
 */
class Guard : IGuard.Stub() {

    private val thread = HandlerThread("automator-guard").apply { start() }
    private val handler = Handler(thread.looper)

    // Touched only on [thread].
    private var component: String? = null
    private var apps: List<KeepAlive.Entry> = emptyList()
    private val backoff = KeepAlive.Backoff()

    private val check = object : Runnable {
        override fun run() {
            component?.let(::ensure)
            apps.forEach(::ensureApp)
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun keep(component: String) {
        handler.post {
            val first = this.component == null
            this.component = component
            if (first) handler.postDelayed(check, CHECK_MS)
        }
    }

    override fun keepApps(lines: Array<out String>?) {
        val entries = lines.orEmpty().mapNotNull(KeepAlive::parse)
        handler.post { apps = entries }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun ensure(component: String) {
        val pkg = component.substringBefore('/')
        if (run("pidof", pkg).isNotBlank()) return
        // Uninstalled: nothing left to keep.
        if (run("pm", "path", pkg).isBlank()) exitProcess(0)
        Log.i(TAG, "$pkg isn't running; starting it")
        run("am", "start-foreground-service", "-n", component)
    }

    private fun ensureApp(app: KeepAlive.Entry) {
        val running = if (app.component != null) run("dumpsys", "activity", "services", app.component).contains("ServiceRecord")
        else run("pidof", app.pkg).isNotBlank()
        val now = SystemClock.elapsedRealtime()
        if (running) {
            backoff.ok(app.pkg)
            return
        }
        if (!backoff.due(app.pkg, now)) return
        val out = if (app.component != null) run("am", "start-foreground-service", "-n", app.component)
        else run("am", "broadcast", "-a", "android.intent.action.BOOT_COMPLETED", "-p", app.pkg)
        // "Error"/"Exception": not allowed as the shell user (a service that isn't exported, a protected broadcast).
        val failed = out.contains("Error") || out.contains("Exception")
        Log.i(TAG, "${app.pkg} isn't running; " + if (failed) "couldn't start it: ${out.trim().lines().firstOrNull()}" else "started it")
        if (failed) backoff.failed(app.pkg, now) else backoff.ok(app.pkg)
    }

    private fun run(vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
    }.getOrDefault("")

    private companion object {
        const val TAG = "AutomatorGuard"
        const val CHECK_MS = 10_000L
    }
}

/** The keep-alive list as the helpers read it, kept apart from Android so it can be checked without a device. */
object KeepAlive {

    data class Entry(val pkg: String, val component: String?)

    /** One "package [service component]" line (see [Background.keepAliveFile]). */
    fun parse(line: String): Entry? {
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val pkg = parts.firstOrNull() ?: return null
        return Entry(pkg, parts.getOrNull(1)?.takeIf { '/' in it })
    }

    /**
     * After a failed start, the next try waits: 1, 2, 4… minutes up to an
     * hour, so an app the helper isn't allowed to start isn't tried every 10 seconds.
     */
    class Backoff {
        private val failures = mutableMapOf<String, Int>()
        private val next = mutableMapOf<String, Long>()

        fun due(pkg: String, now: Long) = now >= (next[pkg] ?: 0L)

        fun failed(pkg: String, now: Long) {
            val n = (failures[pkg] ?: 0) + 1
            failures[pkg] = n
            next[pkg] = now + (60_000L shl (n - 1).coerceAtMost(6)).coerceAtMost(3_600_000L)
        }

        fun ok(pkg: String) {
            failures.remove(pkg)
            next.remove(pkg)
        }
    }
}
