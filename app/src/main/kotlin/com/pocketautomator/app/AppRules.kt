package com.pocketautomator.app

/** Which app counts as the one being played, kept apart from Android so it can be checked without a device. */
object AppRules {

    /**
     * The task whose profile should apply, or null to keep the one in use.
     *
     * - Recents, and Pocket Automator itself, only pass over the app underneath,
     *   so the profile in use stays (and editing a game's profile shows on the game).
     * - A linked app that is showing wins, even when another screen has focus:
     *   on the Duo, touching the bottom screen shouldn't drop a game on the top
     *   screen back to Default. If several show, the focused one wins, then the
     *   top screen's.
     * - Otherwise the focused app (or home screen), which applies Default.
     */
    fun pick(
        focused: TaskEntry?,
        tasks: List<TaskEntry>,
        ownPackage: String,
        linked: (String) -> Boolean,
    ): TaskEntry? {
        if (focused != null && passing(focused, ownPackage)) return null
        val showing = tasks.filter { it.visible && it.component != null && it.type == TaskList.TYPE_STANDARD }
        if (focused != null && focused.type == TaskList.TYPE_STANDARD && focused.pkg?.let(linked) == true) return focused
        showing.sortedBy { if (it.display == 0) 0 else 1 }
            .firstOrNull { it.pkg != ownPackage && it.pkg?.let(linked) == true }
            ?.let { return it }
        if (focused == null || focused.component == null) return null
        return focused.takeIf { it.type == TaskList.TYPE_STANDARD || it.type == TaskList.TYPE_HOME }
    }

    fun passing(task: TaskEntry, ownPackage: String): Boolean =
        task.type == TaskList.TYPE_RECENTS || task.pkg == ownPackage
}
