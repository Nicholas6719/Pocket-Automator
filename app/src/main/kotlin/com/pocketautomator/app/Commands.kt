package com.pocketautomator.app

/**
 * The shell side of every knob: how to read them all in one go, and how to
 * write each one. Everything here runs as the shell user through Shizuku,
 * the same as `adb shell`. Plain functions, so they can be checked without a device.
 */
object Commands {

    /** One line of output per entry, in this order. */
    private val READS = listOf(
        "settings get system performance_mode",
        "settings get system fan_mode",
        "settings get system trigger_input_mode",
        "settings get system screen_brightness_mode",
        "settings get system screen_brightness",
        "settings get system peak_refresh_rate",
        "settings get global zen_mode",
        "settings get global wifi_on",
        "settings get global bluetooth_on",
        // The fan's speed, for the status card: Retroid's settings app drives this node.
        "cat /sys/class/gpio5_pwm2/speed 2>/dev/null || echo null",
    )

    val readScript: String = READS.joinToString("; ")

    data class Readings(val values: Map<Knob, Int>, val fanRpm: Int?)

    /** Reads [readScript]'s output; anything missing or "null" is left out. */
    fun parse(output: String): Readings {
        val lines = output.lines().map { it.trim() }
        fun int(i: Int): Int? = lines.getOrNull(i)?.toIntOrNull()
        fun float(i: Int): Float? = lines.getOrNull(i)?.toFloatOrNull()
        val values = linkedMapOf<Knob, Int>()
        int(0)?.let { values[Knob.PERFORMANCE] = it }
        int(1)?.let { values[Knob.FAN] = it }
        // Retroid's default when it has never been changed is Both.
        values[Knob.TRIGGERS] = int(2) ?: Knob.TRIGGERS_BOTH
        val auto = int(3)
        val level = int(4)
        when {
            auto == 1 -> values[Knob.BRIGHTNESS] = Knob.BRIGHTNESS_AUTO
            level != null -> values[Knob.BRIGHTNESS] = level.coerceIn(1, 255)
        }
        // No stored rate means the system picks: that is its own value.
        values[Knob.REFRESH] = float(5)?.let { Math.round(it) } ?: Knob.REFRESH_DEFAULT
        int(6)?.let { values[Knob.DND] = it }
        // wifi_on / bluetooth_on: 0 off; 1 on; 2 on, and kept on through airplane mode.
        int(7)?.let { values[Knob.WIFI] = if (it == 1 || it == 2) 1 else 0 }
        int(8)?.let { values[Knob.BLUETOOTH] = if (it == 1 || it == 2) 1 else 0 }
        return Readings(values, int(9))
    }

    /** The commands that write [knob] as [value]. [GAME_SCREEN] isn't written here: see [moveTask]. */
    fun write(knob: Knob, value: Int): List<String> = when (knob) {
        Knob.PERFORMANCE -> listOf("settings put system performance_mode $value")
        // The switch goes first, as the Quick Settings tile does it: Retroid's
        // settings app acts on fan_mode changing, and reads the switch then.
        Knob.FAN -> listOf(
            "settings put system smart_fan_mode_switch ${if (value == Knob.FAN_SMART) 1 else 0}",
            "settings put system fan_mode $value",
        )
        Knob.TRIGGERS -> listOf("settings put system trigger_input_mode $value")
        Knob.BRIGHTNESS -> if (value == Knob.BRIGHTNESS_AUTO) {
            listOf("settings put system screen_brightness_mode 1")
        } else {
            listOf(
                "settings put system screen_brightness_mode 0",
                "settings put system screen_brightness ${value.coerceIn(1, 255)}",
            )
        }
        Knob.REFRESH -> if (value == Knob.REFRESH_DEFAULT) {
            listOf("settings delete system min_refresh_rate", "settings delete system peak_refresh_rate")
        } else {
            listOf("settings put system peak_refresh_rate $value.0", "settings put system min_refresh_rate $value.0")
        }
        Knob.DND -> listOf(
            "cmd notification set_dnd " + when (value) {
                Knob.DND_OFF -> "off"
                Knob.DND_SILENCE -> "none"
                Knob.DND_ALARMS -> "alarms"
                else -> "priority"
            },
        )
        Knob.WIFI -> listOf("cmd wifi set-wifi-enabled ${if (value == 1) "enabled" else "disabled"}")
        Knob.BLUETOOTH -> listOf("cmd bluetooth_manager ${if (value == 1) "enable" else "disable"}")
        Knob.GAME_SCREEN -> emptyList()
    }

    /**
     * Every write in one script, each command on its own so one failing
     * doesn't stop the rest. The fan waits a moment after a performance
     * change: Retroid's SystemUI turns the fan off as the mode becomes
     * Standard (within a quarter of a second, seen on the Flip 2), and the
     * fan written after that is the one that stays.
     */
    fun script(writes: Map<Knob, Int>): String =
        writes.flatMap { (knob, value) ->
            val pause = if (knob == Knob.FAN && Knob.PERFORMANCE in writes) listOf("sleep $FAN_DELAY") else emptyList()
            pause + write(knob, value)
        }.joinToString("; ")

    const val FAN_DELAY = "0.8"

    /**
     * Closes [pkg], then clears its Recents cards ([taskIds]). In that order:
     * removing a card kills the app on its own, and a force stop landing in
     * the middle of that made Android start the app again (seen on the Flip 2).
     */
    fun close(pkg: String, taskIds: List<Int>): String =
        (listOf("am force-stop $pkg") + taskIds.map { "am stack remove $it" }).joinToString("; ")

    /** Hands a running app's task to another screen without restarting it. */
    fun moveTask(taskId: Int, displayId: Int): String = "am display move-stack $taskId $displayId"
}
