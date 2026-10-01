package com.pocketautomator.app

/**
 * RetroArch's per-game core options, for [GameSettings]: a game's own options
 * are a file `<config dir>/<core>/<game>.opt` (the ROM's name without its
 * extension). Checked in RetroArch's runloop.c and core_option_manager.c:
 *
 * - With such a file, RetroArch reads that file *instead of* the core's own
 *   options, and any option missing from it takes the core's default. So a
 *   game's file carries every one of the core's options as they are now
 *   ([base]), with the game's own on top.
 * - RetroArch writes the file back only when an option changes while the game
 *   runs (sorted, `key = "value"` per line), so a file that still has what
 *   Pocket Automator wrote is still Pocket Automator's to keep up to date.
 *
 * Kept apart from Android so it can be checked without a device.
 */
object RetroArch {

    const val PACKAGE = "com.retroarch.aarch64"
    val PACKAGES = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")

    /** ES-DE's emulator labels for the cores Pocket Automator knows, and its default core per system. */
    private val LABELS = mapOf(
        "swanstation" to Emulator.SWANSTATION,
        "mupen64plus-next" to Emulator.MUPEN64,
        "flycast" to Emulator.FLYCAST,
    )
    private val DEFAULTS = mapOf(
        "n64" to Emulator.MUPEN64, "n64dd" to Emulator.MUPEN64,
        "dreamcast" to Emulator.FLYCAST, "naomi" to Emulator.FLYCAST, "naomi2" to Emulator.FLYCAST,
        "atomiswave" to Emulator.FLYCAST,
    )

    /** The core ES-DE starts for a game: by its label ("Flycast"; "Flycast (Standalone)" is another app), else the system's default. */
    fun coreFor(label: String?, system: String): Emulator? {
        val l = label?.trim()?.lowercase() ?: return DEFAULTS[system]
        return LABELS[l]
    }

    /** Where RetroArch keeps things, from its retroarch.cfg. */
    data class Setup(
        /** Holds a folder per core, named by its library name. */
        val configDir: String,
        /** RetroArch's "Use game-specific core options" (on unless turned off). */
        val gameOptions: Boolean,
        /** Options per core (`<core>/<core>.opt`) rather than one file for all cores. */
        val perCore: Boolean,
        /** The one file for all cores, used when [perCore] is off or a core has no file yet. */
        val globalFile: String,
    ) {
        fun gameFile(emulator: Emulator, romName: String) =
            "$configDir/${emulator.core}/${romName.substringBeforeLast('.')}.opt"

        fun coreFile(emulator: Emulator) = "$configDir/${emulator.core}/${emulator.core}.opt"
    }

    /** retroarch.cfg's path for the RetroArch app [pkg]. */
    fun configPath(pkg: String) = "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg"

    fun setup(cfg: String, cfgPath: String): Setup {
        val values = parse(cfg)
        val dir = cfgPath.substringBeforeLast('/')
        fun path(key: String, fallback: String) =
            values[key]?.takeIf { it.isNotBlank() && it != "default" }?.removeSuffix("/") ?: fallback
        return Setup(
            configDir = path("rgui_config_directory", "/storage/emulated/0/RetroArch/config"),
            gameOptions = values["game_specific_options"] != "false",
            perCore = values["global_core_options"] != "true",
            globalFile = path("core_options_path", "$dir/retroarch-core-options.cfg"),
        )
    }

    /** `key = "value"` lines, in file order. */
    fun parse(text: String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        text?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=').takeIf { it > 0 } ?: return@forEach
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value = value.substring(1, value.length - 1)
            out[key] = value
        }
        return out
    }

    /** Options as RetroArch writes them: sorted, one `key = "value"` per line. */
    fun render(options: Map<String, String>): String =
        options.toSortedMap().entries.joinToString("") { (k, v) -> "$k = \"$v\"\n" }

    /**
     * [emulator]'s options as RetroArch reads them when a game has none of
     * its own: the core's file, or, without one, its lines in the file for all cores.
     */
    fun base(setup: Setup, emulator: Emulator, read: (String) -> String?): Map<String, String> {
        val own = if (setup.perCore) read(setup.coreFile(emulator)) else null
        val text = own ?: read(setup.globalFile)
        return parse(text).filterKeys { it.startsWith(emulator.prefix) }
    }

    /**
     * Whether a game's file still has what Pocket Automator last wrote
     * ([written]): every option it wrote has that value or is gone (a core
     * update can drop one), and options RetroArch added since don't count.
     */
    fun unchanged(current: Map<String, String>, written: Map<String, String>): Boolean =
        written.all { (k, v) -> current[k] == null || current[k] == v }
}
