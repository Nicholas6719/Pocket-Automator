package com.pocketautomator.app

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.system.exitProcess

/**
 * The helper that starts Pocket Automator again after something stops it: a
 * swipe in Recents that force-stops apps (the Odin 2 Portal's launcher does),
 * or a force stop in Settings. Shizuku runs it as the shell user and keeps it
 * running after Pocket Automator's own process is gone (a "daemon" user
 * service), so it works where the root watchdog can't start.
 *
 * Every [CHECK_MS] it looks for the app's process and, when there is none,
 * starts its service the way `adb shell` would. [GuardLink] starts it while
 * keep-alive is on and stops it when keep-alive is turned off.
 */
class Guard : IGuard.Stub() {

    private val thread = HandlerThread("automator-guard").apply { start() }
    private val handler = Handler(thread.looper)

    // Touched only on [thread].
    private var component: String? = null

    private val check = object : Runnable {
        override fun run() {
            component?.let(::ensure)
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

    private fun run(vararg command: String): String = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
    }.getOrDefault("")

    private companion object {
        const val TAG = "AutomatorGuard"
        const val CHECK_MS = 10_000L
    }
}
