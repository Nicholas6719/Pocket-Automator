package com.pocketautomator.app

import android.content.Context

/**
 * Two lists in Retroid's settings app (com.rp.settings) that can stop Pocket
 * Automator without a word, both comma-separated package names in
 * `Settings.System`:
 *
 * - `auto_run_disabled_packages`: Retroid's settings app switches off the
 *   start-at-boot receivers of every app on it, so automation wouldn't come
 *   back after a restart. Pocket Automator takes itself off it.
 * - `auto_clean_ignored_packages`: when "clean processes on standby"
 *   (`auto_clean_processes`) is on, every app *not* on this list is
 *   force-stopped as the screen goes off. Pocket Automator puts itself on it.
 *
 * Only this app's own entry is ever added or removed.
 */
object RetroidLists {

    const val AUTO_RUN_DISABLED = "auto_run_disabled_packages"
    const val CLEAN_IGNORED = "auto_clean_ignored_packages"

    fun parse(stored: String?): List<String> =
        stored.orEmpty().takeUnless { it == "null" }.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun without(stored: String?, pkg: String): String? {
        val list = parse(stored)
        return if (pkg in list) (list - pkg).joinToString(",") else null
    }

    fun with(stored: String?, pkg: String): String? {
        val list = parse(stored)
        return if (pkg in list) null else (list + pkg).joinToString(",")
    }

    /** Takes this app off the no-auto-run list and onto the cleaner's ignore list. Blocking; needs Shizuku. */
    fun protect(context: Context) {
        if (!Shell.ready) return
        val pkg = context.packageName
        val store = Store.get(context)
        fun get(key: String) = Shell.run("settings", "get", "system", key).out.trim()
        fun put(key: String, value: String) {
            if (value.isEmpty()) Shell.run("settings", "delete", "system", key)
            else Shell.run("settings", "put", "system", key, value)
        }
        without(get(AUTO_RUN_DISABLED), pkg)?.let {
            put(AUTO_RUN_DISABLED, it)
            store.log("taken off Retroid's no-auto-run list")
        }
        with(get(CLEAN_IGNORED), pkg)?.let {
            put(CLEAN_IGNORED, it)
            store.log("added to Retroid's standby-cleaner ignore list")
        }
    }
}
