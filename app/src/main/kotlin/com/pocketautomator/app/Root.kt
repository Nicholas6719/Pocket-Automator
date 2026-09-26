package com.pocketautomator.app

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Root commands through Retroid's own root service, `PServerBinder`: the one
 * behind "Run script as Root" in Retroid's settings. Retroid's settings app
 * sends it a string array [command, "1"] with transaction 0 and reads the
 * output back as a byte array; commands run as uid 0. Found on the Flip 2
 * (RPFlip2_V1.0.0.130); on firmware without it, [available] is false and
 * everything that needs root is skipped.
 *
 * Used for what Shizuku can't do for itself: starting Shizuku after a
 * restart, and the keep-alive watchdog. Blocking: use a background thread.
 */
object Root {

    private const val TAG = "AutomatorRoot"
    private const val SERVICE = "PServerBinder"

    private fun binder(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as IBinder?
    }.onFailure { Log.w(TAG, "no $SERVICE", it) }.getOrNull()?.takeIf { it.isBinderAlive }

    val available: Boolean get() = binder() != null

    /** Runs [command] as root and returns its output, or null when the service isn't there. */
    fun run(command: String): String? {
        val binder = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(command, "1"))
            binder.transact(0, data, reply, 0)
            reply.createByteArray()?.decodeToString().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "root command failed: $command", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Whether commands really run as root ("uid=0" from `id`). */
    fun works(): Boolean = run("id")?.contains("uid=0") == true
}

/** A command as root if Retroid's root service is there, otherwise through Shizuku. Blocking. */
object Privileged {
    fun sh(script: String): String? =
        Root.run(script) ?: if (Shell.ready) runCatching { Shell.sh(script).out }.getOrNull() else null
}
