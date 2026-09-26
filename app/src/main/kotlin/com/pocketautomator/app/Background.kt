package com.pocketautomator.app

import android.content.Context

/**
 * Keeping things running 24/7: Shizuku, Pocket Automator itself, and the apps
 * on the keep-alive list (Syncthing Fork, say).
 *
 * Two layers. Each app is first taken off everything that stops apps in the
 * background: Android's battery restrictions (Doze allowlist, background
 * app-ops, standby bucket) and Retroid's standby cleaner and no-auto-run
 * list. Then a small root watchdog (assets/watchdog.sh), started through
 * [Root] after every boot, checks every 20 seconds and starts anything that
 * has stopped, including after a swipe in Recents or a force stop.
 *
 * Blocking: use a background thread.
 */
object Background {

    const val DIR = "/data/local/tmp/pocket-automator"
    const val SHIZUKU = Shell.SHIZUKU_PACKAGE

    /** Apps with a service that is better started directly than woken by the boot broadcast. */
    val KNOWN_SERVICES = mapOf(
        "com.github.catfriend1.syncthingfork" to
            "com.github.catfriend1.syncthingfork/com.nutomic.syncthingandroid.service.SyncthingService",
    )

    /** What the watchdog reads: one "package [component]" line per app. */
    fun keepAliveFile(packages: Collection<String>): String =
        packages.sorted().joinToString("\n", postfix = "\n") { pkg ->
            KNOWN_SERVICES[pkg]?.let { "$pkg $it" } ?: pkg
        }

    /** The commands that take [pkg] off Android's background limits. */
    fun protectCommands(pkg: String): List<String> = listOf(
        "dumpsys deviceidle whitelist +$pkg",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        "am set-standby-bucket $pkg active",
    )

    /** The command that starts Shizuku's server, run as root. */
    const val START_SHIZUKU =
        "lib=\$(pm path $SHIZUKU | head -n 1 | sed 's/^package://; s/base\\.apk\$//')lib/arm64/libshizuku.so; [ -f \"\$lib\" ] && \"\$lib\""

    data class Status(
        val root: Boolean,
        val watchdog: Boolean,
        val shizuku: Boolean,
        /** Keep-alive packages and whether each is running. */
        val apps: Map<String, Boolean>,
    )

    /** Starts Shizuku as root if it isn't running. Returns whether it is running afterwards. */
    fun startShizuku(): Boolean {
        if (Shell.ready || running("shizuku_server")) return true
        Root.run(START_SHIZUKU) ?: return false
        // The starter returns as it hands over to the server, which takes a moment to show up.
        repeat(10) {
            if (running("shizuku_server")) return true
            Thread.sleep(500)
        }
        return false
    }

    /**
     * Puts everything in place: protections, the keep-alive list, and the
     * watchdog (started if it isn't running). With [enabled] false the
     * watchdog is told to stop instead.
     */
    fun apply(context: Context, enabled: Boolean, keepAlive: Set<String>) {
        if (!Root.works()) return
        val store = Store.get(context)
        val script = context.assets.open("watchdog.sh").use { it.readBytes().decodeToString() }
        val file = java.io.File(context.filesDir, "watchdog.sh").apply { writeText(script) }
        val list = java.io.File(context.filesDir, "keepalive").apply { writeText(keepAliveFile(keepAlive)) }

        val everyone = keepAlive + context.packageName + SHIZUKU
        Root.run(everyone.flatMap(::protectCommands).joinToString("; "))
        RetroidLists.protectAll(everyone)

        // A running sh reads its script as it goes, so a new version is moved
        // into place (the running one keeps the old file) and then restarted.
        val updated = Root.run(
            "mkdir -p $DIR; cp ${list.absolutePath} $DIR/keepalive; " +
                "if ! cmp -s ${file.absolutePath} $DIR/watchdog.sh; then cp ${file.absolutePath} $DIR/watchdog.sh.new; " +
                "chmod 755 $DIR/watchdog.sh.new; mv $DIR/watchdog.sh.new $DIR/watchdog.sh; echo updated; fi",
        )?.contains("updated") == true
        if (!enabled) {
            Root.run("rm -f $DIR/enabled")
            store.log("keep-alive watchdog switched off")
            return
        }
        Root.run("touch $DIR/enabled")
        if (updated && watchdogRunning()) {
            Root.run("kill \$(cat $DIR/watchdog.pid) 2>/dev/null; rm -f $DIR/watchdog.pid")
            store.log("keep-alive watchdog updated")
        }
        if (!watchdogRunning()) {
            Root.run("setsid sh $DIR/watchdog.sh </dev/null >/dev/null 2>&1 &")
            store.log(if (watchdogRunning()) "keep-alive watchdog started" else "keep-alive watchdog didn't start")
        }
    }

    fun watchdogRunning(): Boolean {
        val out = Root.run("pid=\$(cat $DIR/watchdog.pid 2>/dev/null); [ -n \"\$pid\" ] && kill -0 \$pid 2>/dev/null && echo yes")
        return out?.trim() == "yes"
    }

    fun running(process: String): Boolean =
        (Root.run("pidof $process") ?: runCatching { Shell.run("pidof", process).out }.getOrNull())
            ?.trim()?.isNotEmpty() == true

    fun status(keepAlive: Set<String>): Status {
        val root = Root.works()
        return Status(
            root = root,
            watchdog = root && watchdogRunning(),
            shizuku = Shell.ready || running("shizuku_server"),
            apps = keepAlive.associateWith { running(it) },
        )
    }

    /** The watchdog's own log, newest last. */
    fun watchdogLog(): List<String> =
        Root.run("tail -n 30 $DIR/watchdog.log 2>/dev/null")?.lines()?.filter { it.isNotBlank() }.orEmpty()

    /** Closes [pkg]: its Recents cards first, then the app itself. */
    fun close(pkg: String, taskIds: List<Int>) {
        val commands = taskIds.map { "am stack remove $it" } + "am force-stop $pkg"
        val script = commands.joinToString("; ")
        if (Root.run(script) == null && Shell.ready) Shell.sh(script)
    }
}
