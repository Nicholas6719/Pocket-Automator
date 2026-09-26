package com.pocketautomator.app

import android.os.Build

/**
 * Which handheld this is, and what it offers.
 *
 * The Flip 2's numbers were read off its own firmware (RPFlip2_V1.0.0.130,
 * Android 13): the Quick Settings Performance and Fan tiles, the settings they
 * write, the CPU limits and fan duty that follow. The Duo was not out yet, so
 * its entries are the Flip 2's until they can be checked on one; [verified]
 * says which is which.
 */
object Device {

    enum class Model(val title: String, val verified: Boolean) {
        FLIP2("Retroid Pocket Flip 2", verified = true),
        DUO("Retroid Pocket Duo", verified = false),
        OTHER_RETROID("Retroid handheld", verified = false),
        UNSUPPORTED("Not a Retroid", verified = false),
    }

    data class Option(val value: Int, val label: String)

    val current: Model by lazy { model(Build.MANUFACTURER, Build.MODEL) }

    /**
     * Retroid's builds report the manufacturer as "Moorechip" and the model
     * as "Retroid Pocket …" ("Retroid Pocket Flip2" on the Flip 2).
     */
    fun model(manufacturer: String, model: String): Model {
        val name = model.lowercase().replace(" ", "")
        val retroid = name.startsWith("retroidpocket") || manufacturer.equals("Retroid", ignoreCase = true)
        return when {
            !retroid -> Model.UNSUPPORTED
            name.startsWith("retroidpocketflip2") -> Model.FLIP2
            // The Duo Lite has a different chip, and nobody asked for it yet.
            name.startsWith("retroidpocketduo") && "lite" !in name -> Model.DUO
            else -> Model.OTHER_RETROID
        }
    }

    val PERFORMANCE = listOf(
        Option(Knob.PERF_STANDARD, "Standard"),
        Option(Knob.PERF_PERFORMANCE, "Performance"),
        Option(Knob.PERF_HIGH, "High Performance"),
    )

    /** The Quick Settings tile offers Quiet, Smart and Sport; Retroid's settings app can also turn the fan off. */
    val FAN = listOf(
        Option(Knob.FAN_QUIET, "Quiet"),
        Option(Knob.FAN_SMART, "Smart"),
        Option(Knob.FAN_SPORT, "Sport"),
        Option(Knob.FAN_OFF, "Off"),
    )

    val TRIGGERS = listOf(
        Option(Knob.TRIGGERS_ANALOG, "Analog"),
        Option(Knob.TRIGGERS_DIGITAL, "Digital"),
        Option(Knob.TRIGGERS_BOTH, "Both"),
    )

    val DND = listOf(
        Option(Knob.DND_PRIORITY, "On"),
        Option(Knob.DND_OFF, "Off"),
    )

    val ON_OFF = listOf(Option(1, "On"), Option(0, "Off"))

    val GAME_SCREEN = listOf(
        Option(Knob.SCREEN_TOP, "Top screen"),
        Option(Knob.SCREEN_BOTTOM, "Bottom screen"),
    )

    fun options(knob: Knob, refreshRates: List<Int>): List<Option> = when (knob) {
        Knob.PERFORMANCE -> PERFORMANCE
        Knob.FAN -> FAN
        Knob.TRIGGERS -> TRIGGERS
        Knob.DND -> DND
        Knob.WIFI, Knob.BLUETOOTH -> ON_OFF
        Knob.GAME_SCREEN -> GAME_SCREEN
        Knob.REFRESH -> refreshRates.map { Option(it, "$it Hz") }
        // Brightness is a slider, not a list.
        Knob.BRIGHTNESS -> emptyList()
    }

    fun title(knob: Knob): String = when (knob) {
        Knob.PERFORMANCE -> "Performance mode"
        Knob.FAN -> "Fan"
        Knob.TRIGGERS -> "L2/R2 mode"
        Knob.BRIGHTNESS -> "Brightness"
        Knob.REFRESH -> "Refresh rate"
        Knob.DND -> "Do Not Disturb"
        Knob.WIFI -> "Wi-Fi"
        Knob.BLUETOOTH -> "Bluetooth"
        Knob.GAME_SCREEN -> "Game screen"
    }

    /** How a value reads, for summaries and the switch message. */
    fun describe(knob: Knob, value: Int): String = when (knob) {
        Knob.BRIGHTNESS -> if (value == Knob.BRIGHTNESS_AUTO) "Auto" else "${brightnessPercent(value)}%"
        Knob.REFRESH -> if (value == Knob.REFRESH_DEFAULT) "Default" else "$value Hz"
        Knob.DND -> when (value) {
            Knob.DND_OFF -> "Off"
            Knob.DND_SILENCE -> "Total silence"
            Knob.DND_ALARMS -> "Alarms only"
            else -> "On"
        }
        else -> options(knob, emptyList()).firstOrNull { it.value == value }?.label ?: value.toString()
    }

    /** The knobs this device can offer, given its screens. */
    fun knobs(refreshRates: List<Int>, screens: Int): List<Knob> = Knob.entries.filter {
        when (it) {
            Knob.REFRESH -> refreshRates.size > 1
            Knob.GAME_SCREEN -> screens > 1
            else -> true
        }
    }

    /** 1..255 as the percent the brightness slider shows, and back. */
    fun brightnessPercent(raw: Int): Int = ((raw.coerceIn(1, 255) * 100 + 127) / 255).coerceIn(1, 100)

    fun brightnessRaw(percent: Int): Int = ((percent.coerceIn(1, 100) * 255 + 50) / 100).coerceIn(1, 255)

    /** Emulators that usually want more power, offered first and linked by the starter "Heavy" profile. */
    val HEAVY = listOf(
        "com.armsx2",
        "xyz.aethersx2.android",
        "xyz.aethersx2.android.nethersx2",
        "org.dolphinemu.dolphinemu",
        "org.dolphinemu.mmjr",
        "dev.eden.eden_emulator",
        "io.github.citron_emu.citron",
        "org.sudachi.sudachi_emu",
        "org.yuzu.yuzu_emu",
        "org.azahar_emu.azahar",
        "io.github.lime3ds.android",
        "org.citra.emu",
        "org.vita3k.emulator",
        "app.gamenative",
        "com.winlator",
        "com.gamehub.lite",
    )

    /** Emulators for older systems, linked by the starter "Retro" profile. */
    val RETRO = listOf(
        "com.retroarch.aarch64",
        "com.retroarch",
        "com.retroarch.ra32",
    )

    /** Other emulators and frontends, offered near the top of the app list. */
    val OTHER_EMULATORS = listOf(
        "org.ppsspp.ppsspp",
        "org.ppsspp.ppssppgold",
        "com.github.stenzek.duckstation",
        "me.magnum.melonds",
        "me.magnum.melonds.nightly",
        "com.dsemu.drastic",
        "com.flycast.emulator",
        "org.mupen64plusae.v3.fzurita",
        "com.explusalpha.Snes9xPlus",
        "org.es_de.frontend",
        "com.magneticchen.daijishou",
    )
}
