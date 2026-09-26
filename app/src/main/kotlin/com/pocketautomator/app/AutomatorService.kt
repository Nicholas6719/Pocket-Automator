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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        scope.launch {
            store.enabled.drop(1).collect { on ->
                watcher.refresh()
                // Turning automation off leaves the device as Default has it,
                // not as the last game did.
                if (!on) automator.applyNow(store.default())
            }
        }
        store.log("service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        current = null
        scope.cancel()
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
