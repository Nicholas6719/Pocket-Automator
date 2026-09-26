package com.pocketautomator.app

/**
 * One thing a profile can set. Each value is the number the device itself
 * stores, so a value read from the device can be written straight back.
 *
 * - [PERFORMANCE]: `Settings.System performance_mode` (Retroid's own).
 * - [FAN]: `Settings.System fan_mode` (Retroid's own; Smart also sets `smart_fan_mode_switch`).
 * - [TRIGGERS]: `Settings.System trigger_input_mode`, the L2/R2 mode (Retroid's own).
 * - [BRIGHTNESS]: 1..255 is `screen_brightness` with auto-brightness off; [BRIGHTNESS_AUTO] is auto.
 * - [REFRESH]: a refresh rate in Hz; [REFRESH_DEFAULT] is the system's own choice.
 * - [DND]: `Settings.Global zen_mode` (0 off, 1 priority only, 2 total silence, 3 alarms only).
 * - [WIFI], [BLUETOOTH]: 1 on, 0 off.
 * - [GAME_SCREEN]: which screen of a dual-screen device the game is moved to (0 top, 1 bottom).
 *   It is a placement rather than a setting, so it is never put back afterwards.
 */
enum class Knob(val key: String, val restorable: Boolean = true) {
    PERFORMANCE("performance"),
    FAN("fan"),
    TRIGGERS("triggers"),
    BRIGHTNESS("brightness"),
    REFRESH("refresh"),
    DND("dnd"),
    WIFI("wifi"),
    BLUETOOTH("bluetooth"),
    GAME_SCREEN("gameScreen", restorable = false);

    companion object {
        fun byKey(key: String): Knob? = entries.firstOrNull { it.key == key }

        const val BRIGHTNESS_AUTO = -1
        const val REFRESH_DEFAULT = 0

        const val PERF_STANDARD = 0
        const val PERF_PERFORMANCE = 1
        const val PERF_HIGH = 2

        const val FAN_OFF = 0
        const val FAN_QUIET = 1
        const val FAN_SMART = 4
        const val FAN_SPORT = 5

        const val TRIGGERS_ANALOG = 0
        const val TRIGGERS_DIGITAL = 1
        const val TRIGGERS_BOTH = 2

        const val DND_OFF = 0
        const val DND_PRIORITY = 1
        const val DND_SILENCE = 2
        const val DND_ALARMS = 3

        const val SCREEN_TOP = 0
        const val SCREEN_BOTTOM = 1
    }
}

/** A profile: what it sets (knobs it leaves alone are absent), and the apps that use it. */
data class Profile(
    val id: Int,
    val name: String,
    val settings: Map<Knob, Int> = emptyMap(),
    val apps: Set<String> = emptySet(),
    /** Whether its apps are closed once left behind (see [AutoClose]). */
    val autoClose: Boolean = true,
) {
    val isDefault: Boolean get() = id == DEFAULT_ID

    companion object {
        /** The profile that applies whenever no linked app is in front. It can't be deleted. */
        const val DEFAULT_ID = 0
        const val MAX_NAME = 24
    }
}
