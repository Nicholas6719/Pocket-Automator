package com.pocketautomator.app

/**
 * The rules for switching profiles, kept apart from Android so they can be
 * checked without a device.
 *
 * What the device should look like while a profile is in use is:
 * the profile's own settings; then, for anything it leaves alone, the Default
 * profile's; then, for anything neither of them sets, whatever it was before
 * a game changed it. That last part is the *snapshot*: the first time a game
 * profile changes something the Default profile doesn't set (say, Wi-Fi off),
 * the value it had is kept, and put back when a profile that doesn't set it
 * takes over. So a game can't leave Wi-Fi off behind it.
 */
object Plan {

    data class Step(
        /** What to write, only where it differs from what the device has now. */
        val writes: Map<Knob, Int>,
        /** The snapshot to keep afterwards. */
        val snapshot: Map<Knob, Int>,
    )

    /**
     * Switching to [target], with [base] the Default profile's settings,
     * [current] what the device has now (a knob that couldn't be read is
     * absent), and [snapshot] what was kept from before.
     */
    fun switchTo(
        target: Map<Knob, Int>,
        base: Map<Knob, Int>,
        current: Map<Knob, Int>,
        snapshot: Map<Knob, Int>,
    ): Step {
        val writes = linkedMapOf<Knob, Int>()
        val kept = snapshot.toMutableMap()
        var fan: Int? = null
        for (knob in Knob.entries) {
            if (!knob.restorable) continue
            val mine = target[knob]
            val fallback = base[knob]
            val desired = when {
                mine != null -> {
                    if (fallback == null && knob !in kept) current[knob]?.let { kept[knob] = it }
                    mine
                }
                fallback != null -> fallback
                else -> kept[knob]
            }
            // The Default profile owns a knob it sets, and a knob no profile
            // now sets goes back to its old value: either way the snapshot's
            // copy is done with.
            if (fallback != null || mine == null) kept.remove(knob)
            if (knob == Knob.FAN) fan = desired
            if (desired != null && desired != current[knob]) writes[knob] = desired
        }
        // Retroid's firmware turns the fan off whenever the performance mode
        // becomes Standard, so a performance change always rewrites the fan:
        // the one wanted, or the one it had.
        if (Knob.PERFORMANCE in writes && Knob.FAN !in writes) {
            (fan ?: current[Knob.FAN])?.let { writes[Knob.FAN] = it }
        }
        return Step(writes, kept)
    }

    /** The profile that applies while [pkg] is in front: the one it is linked to, else Default. */
    fun profileFor(profiles: List<Profile>, pkg: String?): Profile =
        pkg?.let { app -> profiles.firstOrNull { !it.isDefault && app in it.apps } }
            ?: profiles.firstOrNull { it.isDefault }
            ?: Profile(Profile.DEFAULT_ID, "Default")

    /** [profiles] after linking [apps] to [id]: an app uses one profile, so it leaves any other. */
    fun relinked(profiles: List<Profile>, id: Int, apps: Set<String>): List<Profile> =
        profiles.map {
            when {
                it.id == id -> it.copy(apps = if (it.isDefault) emptySet() else apps)
                else -> it.copy(apps = it.apps - apps)
            }
        }

    /** A name no other profile has; blank or taken names get a number. */
    fun uniqueName(profiles: List<Profile>, wanted: String, except: Int? = null): String {
        val base = wanted.trim().take(Profile.MAX_NAME).ifBlank { "Profile" }
        val taken = profiles.filter { it.id != except }.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base $n".lowercase() in taken) n++
        return "$base $n"
    }
}
