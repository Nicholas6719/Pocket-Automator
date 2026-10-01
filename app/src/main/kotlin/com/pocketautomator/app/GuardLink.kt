package com.pocketautomator.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

/**
 * Keeps [Guard] running while keep-alive is on and Shizuku is up, and stops
 * it when keep-alive is turned off. Main thread only.
 */
class GuardLink(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val store = Store.get(context)
    private var bound = false
    private var started = false
    private var guard: IGuard? = null

    private val args by lazy {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(1)
        Shizuku.UserServiceArgs(ComponentName(context.packageName, Guard::class.java.name))
            .processNameSuffix("guard")
            .tag("guard")
            .debuggable(false)
            // Outlives this app's process: that's the point of it.
            .daemon(true)
            .version(version)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val guard = binder?.takeIf { it.pingBinder() }?.let { IGuard.Stub.asInterface(it) } ?: return
            runCatching { guard.keep(ComponentName(context, AutomatorService::class.java).flattenToString()) }
                .onFailure { store.log("couldn't start the restart helper: ${it.message}") }
            this@GuardLink.guard = guard
            running = true
            sendApps()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            guard = null
            running = false
            // It stopped by itself: look again shortly.
            handler.postDelayed({ if (bound) { bound = false; refresh() } }, RETRY_MS)
        }
    }

    private val shizukuUp = Shizuku.OnBinderReceivedListener { refresh() }
    private val shizukuGone = Shizuku.OnBinderDeadListener {
        bound = false
        guard = null
        running = false
    }

    /** The keep-alive list, for the helper to keep running too. */
    fun sendApps() {
        val guard = guard ?: return
        val lines = Background.keepAliveFile(store.keepAlive.value).lines().filter { it.isNotBlank() }
        runCatching { guard.keepApps(lines.toTypedArray()) }
    }

    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(shizukuUp, handler)
        Shizuku.addBinderDeadListener(shizukuGone, handler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(shizukuUp)
        Shizuku.removeBinderDeadListener(shizukuGone)
        // Leaves the helper running: this is the app going away, which is what it's for.
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, false) }
        bound = false
        guard = null
    }

    /** Starts the helper when keep-alive is on, and stops it (for good) when it's off. */
    fun refresh() {
        if (!started) return
        val wanted = store.keepAliveOn.value && Shell.ready
        if (wanted && !bound) {
            bound = runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure { store.log("couldn't start the restart helper: ${it.message}") }
                .isSuccess
        } else if (!wanted && bound && Shell.ready) {
            bound = false
            guard = null
            running = false
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    companion object {
        private const val RETRY_MS = 5_000L

        /** Whether the helper is running and watching, for the Keep alive page. */
        @Volatile
        var running = false
            private set
    }
}
