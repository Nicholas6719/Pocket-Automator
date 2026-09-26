package com.pocketautomator.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Keeps [Automator] told which app is in front, while automation is on and
 * Shizuku is running.
 *
 * The facts come from [TaskWatcher], which Shizuku runs as a user service;
 * this binds it when it is needed and lets it go when it isn't. Adapted from
 * Thor Pathfinder's AppWatcher (GPL-3.0). Main thread only.
 */
class AppWatcher(
    private val context: Context,
    private val automator: Automator,
    /** Called once Shizuku is up and allowed, for the one-time grants. */
    private val onShizukuReady: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val store = Store.get(context)
    private var bound = false
    private var started = false
    private var retries = 0
    private var pauseUntil = 0L
    private var focused: TaskEntry? = null
    private var tasks: List<TaskEntry> = emptyList()

    private val args by lazy {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(1)
        Shizuku.UserServiceArgs(ComponentName(context.packageName, TaskWatcher::class.java.name))
            .processNameSuffix("tasks")
            .tag("tasks")
            .debuggable(false)
            // Tied to this app's process: it goes when the app does.
            .daemon(false)
            // A new version restarts it, so an update never talks to an old helper.
            .version(version)
    }

    private val listener = object : ITaskListener.Stub() {
        override fun onTasks(focused: String?, tasks: Array<out String>?) {
            val top = TaskList.parse(focused)
            val all = tasks.orEmpty().mapNotNull { TaskList.parse(it) }
            handler.post {
                retries = 0
                this@AppWatcher.focused = top
                this@AppWatcher.tasks = all
                automator.tasks = all
                decide()
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val watcher = binder?.takeIf { it.pingBinder() }?.let { ITaskWatcher.Stub.asInterface(it) } ?: return
            runCatching { watcher.watch(listener) }
                .onFailure { store.log("couldn't start watching apps: ${it.message}") }
            store.setNow { it.copy(watching = true) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handler.post {
                store.setNow { it.copy(watching = false) }
                if (!bound) return@post
                // It stopped by itself: start another after a pause, a few times at most.
                retries++
                if (retries > RETRIES) {
                    pauseUntil = Long.MAX_VALUE
                    store.log("stopped watching apps: the helper kept stopping")
                } else {
                    Log.w(TAG, "the helper stopped; starting it again ($retries)")
                    pauseUntil = SystemClock.uptimeMillis() + RETRY_MS
                    handler.postDelayed({ refresh() }, RETRY_MS)
                }
                unbind()
                automator.forget()
            }
        }
    }

    private val shizukuUp = Shizuku.OnBinderReceivedListener {
        retries = 0
        pauseUntil = 0L
        if (Shell.ready) onShizukuReady()
        refresh()
    }

    private val shizukuGone = Shizuku.OnBinderDeadListener {
        bound = false
        store.setNow { it.copy(watching = false) }
        automator.forget()
    }

    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ ->
        if (Shell.ready) onShizukuReady()
        refresh()
    }

    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(shizukuUp, handler)
        Shizuku.addBinderDeadListener(shizukuGone, handler)
        Shizuku.addRequestPermissionResultListener(permission, handler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(shizukuUp)
        Shizuku.removeBinderDeadListener(shizukuGone)
        Shizuku.removeRequestPermissionResultListener(permission)
        unbind()
        automator.forget()
    }

    /** Binds the helper when there is something to watch for, and lets it go when not. */
    fun refresh() {
        if (!started) return
        val wanted = store.enabled.value && Shell.ready
        if (wanted && !bound && SystemClock.uptimeMillis() >= pauseUntil) {
            bound = runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure { store.log("couldn't start the app watcher: ${it.message}") }
                .isSuccess
        } else if (!wanted && bound) {
            unbind()
            automator.forget()
        }
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        store.setNow { it.copy(watching = false) }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    private fun decide() {
        if (!bound) return
        val profiles = store.profiles.value
        val linked = profiles.filter { !it.isDefault }.flatMap { it.apps }.toSet()
        val task = AppRules.pick(focused, tasks, context.packageName) { it in linked }
        if (task == null) automator.ensureApplied() else automator.onApp(task)
    }

    /** Profiles changed: the app in front may now use another one. */
    fun reconsider() {
        decide()
        automator.onProfilesChanged()
    }

    private companion object {
        const val TAG = "AutomatorApps"
        const val RETRY_MS = 2000L
        const val RETRIES = 3
    }
}
