package com.pocketautomator.app

import android.app.Application

/**
 * Pocket Automator's process, whatever started it. Other apps can wake the
 * process on its own (Shizuku does, to hand over its connection), so the
 * service is started here too: a process without it would do nothing.
 */
class AutomatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only the app's own process: Shizuku runs the task watcher from this APK too.
        if (getProcessName() == packageName) AutomatorService.start(this)
    }
}
