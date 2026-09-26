package com.pocketautomator.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.pocketautomator.app.ui.MainActivity
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * What keeps automation running while games are played: a foreground service
 * (Android would stop anything less once the app is in the background) that
 * owns the [AppWatcher] and the [Automator]. Its notification sits in the
 * quietest channel; Pocket Automator never asks to post notifications, so on
 * Android 13 it doesn't show in the shade at all.
 */
class AutomatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val grants = Executors.newSingleThreadExecutor()
    private lateinit var automator: Automator
    private lateinit var watcher: AppWatcher

    override fun onCreate() {
        super.onCreate()
        running = true
        startInForeground()
        val store = Store.get(this)
        automator = Automator(this).also { current = it }
        watcher = AppWatcher(this, automator, ::onShizukuReady)
        watcher.start()
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF))
        scope.launch { store.profiles.drop(1).collect { watcher.reconsider() } }
        // Games' own settings: handheld ones take effect now, emulator ones go into the emulators' files.
        scope.launch { store.games.drop(1).collect { watcher.reconsider() } }
        scope.launch { store.games.collect { GameSettings.requestSync(this@AutomatorService) } }
        scope.launch { store.gameDetection.drop(1).collect { GameSettings.requestSync(this@AutomatorService) } }
        scope.launch { store.suggestAuto.drop(1).collect { GameSettings.requestSync(this@AutomatorService) } }
        scope.launch {
            store.enabled.drop(1).collect { on ->
                watcher.refresh()
                // Turning automation off leaves the device as Default has it,
                // not as the last game did.
                if (!on) automator.applyNow(store.default())
            }
        }
        // Keep-alive: start Shizuku if it's down, and the watchdog, now and whenever the settings change.
        scope.launch {
            combine(store.keepAliveOn, store.keepAlive) { on, apps -> on to apps }.collect { (on, apps) ->
                grants.execute {
                    runCatching { keepAlive(on, apps) }
                        .onFailure { store.log("keep-alive setup failed: ${it.javaClass.simpleName}: ${it.message}") }
                }
            }
        }
        Shizuku.addBinderDeadListener(shizukuGone)
        store.log("service started")
    }

    /** Shizuku stopped: with keep-alive on, start it again rather than wait for the watchdog's next look. */
    private val shizukuGone = Shizuku.OnBinderDeadListener {
        grants.execute {
            Thread.sleep(3_000)
            val store = Store.get(this)
            if (store.keepAliveOn.value && !Shell.ready && Background.startShizuku()) store.log("Shizuku stopped; started it again")
        }
    }

    /** Blocking: runs on [grants]. */
    private fun keepAlive(on: Boolean, apps: Set<String>) {
        val store = Store.get(this)
        if (!Root.works()) {
            store.log("keep-alive needs Retroid's root service, which isn't available")
            return
        }
        if (!store.keepAliveSeeded) {
            store.keepAliveSeeded = true
            val defaults = Background.KNOWN_SERVICES.keys.filter { pkg ->
                runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }
            if (defaults.isNotEmpty()) {
                // Setting the list runs this again with it.
                store.setKeepAlive(apps + defaults)
                return
            }
        }
        if (on && !Shell.ready) {
            store.log(if (Background.startShizuku()) "started Shizuku as root" else "couldn't start Shizuku")
        }
        Background.apply(this, on, apps)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        current = null
        scope.cancel()
        Shizuku.removeBinderDeadListener(shizukuGone)
        runCatching { unregisterReceiver(screen) }
        watcher.stop()
        automator.release()
        super.onDestroy()
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) automator.onScreenOff()
        }
    }

    /**
     * The one-time things Shizuku makes possible: drawing the switch message
     * over games, and staying on Retroid's lists of apps it leaves alone.
     */
    private fun onShizukuReady() {
        grants.execute {
            if (!Settings.canDrawOverlays(this)) {
                Shell.run("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow")
            }
            if (!android.os.Environment.isExternalStorageManager()) {
                Shell.run("appops", "set", packageName, "MANAGE_EXTERNAL_STORAGE", "allow")
            }
            RetroidLists.protect(this)
            automator.refreshReadings()
            GameSettings.requestSync(this)
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Automation running", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Pocket Automator watches which game is in front."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pocket Automator")
            .setContentText("Switching profiles as your games open")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "running"
        private const val ID = 1

        @Volatile var running = false
            private set

        /** The running service's automator, for the screens' Apply button and readings. */
        @Volatile var current: Automator? = null
            private set

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AutomatorService::class.java)) }
                .onFailure { Store.get(context).log("couldn't start the service: ${it.message}") }
        }
    }
}

/** Starts the service after a restart, and after an update replaces the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> AutomatorService.start(context)
        }
    }
}
