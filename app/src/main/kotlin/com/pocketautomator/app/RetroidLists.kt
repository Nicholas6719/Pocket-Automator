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

    /** Takes this app off the no-auto-run list and onto the cleaner's ignore list. Blocking. */
    fun protect(context: Context) {
        if (protectAll(listOf(context.packageName))) Store.get(context).log("protected from Retroid's cleaner and no-auto-run list")
    }

    /**
     * Takes [packages] off the no-auto-run list and puts them on the standby
     * cleaner's ignore list. Returns whether anything changed. Blocking; needs root or Shizuku.
     */
    fun protectAll(packages: Collection<String>): Boolean {
        fun get(key: String) = Privileged.sh("settings get system $key")?.trim()
        val runBefore = get(AUTO_RUN_DISABLED)
        val cleanBefore = get(CLEAN_IGNORED)
        val runAfter = packages.fold(runBefore) { stored, pkg -> without(stored, pkg) ?: stored }
        val cleanAfter = packages.fold(cleanBefore) { stored, pkg -> with(stored, pkg) ?: stored }
        var changed = false
        if (runAfter != runBefore) {
            Privileged.sh(if (runAfter.isNullOrEmpty()) "settings delete system $AUTO_RUN_DISABLED" else "settings put system $AUTO_RUN_DISABLED '$runAfter'")
            changed = true
        }
        if (cleanAfter != cleanBefore && !cleanAfter.isNullOrEmpty()) {
            Privileged.sh("settings put system $CLEAN_IGNORED '$cleanAfter'")
            changed = true
        }
        return changed
    }
}
