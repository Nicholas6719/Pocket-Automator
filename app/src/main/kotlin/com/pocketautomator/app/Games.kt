package com.pocketautomator.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One game's own settings, on top of its emulator's profile: handheld
 * settings ([settings], which win over the profile's) and emulator settings
 * ([emu], keyed by [EmuKnob.key]). A game is named as ES-DE names it: its
 * system and ROM file name.
 */
data class GameProfile(
    val system: String,
    /** The ROM file name without its extension, as [EsDe.Game.file]. */
    val file: String,
    val name: String,
    /** The ROM as the gamelist has it ("./Mario Kart 7.cci"). */
    val path: String,
    val settings: Map<Knob, Int> = emptyMap(),
    val emu: Map<String, String> = emptyMap(),
) {
    val id: String get() = id(system, file)
    val isEmpty: Boolean get() = settings.isEmpty() && emu.isEmpty()

    /** The ROM's file name with its extension. */
    val romName: String get() = path.substringAfterLast('/')

    companion object {
        fun id(system: String, file: String) = "$system/$file"

        fun of(game: EsDe.Game) = GameProfile(game.system, game.file, game.name, game.path)
    }
}

object GamesJson {

    fun toJson(games: List<GameProfile>): JSONArray = JSONArray().apply {
        games.filter { !it.isEmpty }.sortedBy { it.id }.forEach { g ->
            put(JSONObject().apply {
                put("system", g.system)
                put("file", g.file)
                put("name", g.name)
                put("path", g.path)
                put("settings", ProfilesJson.settingsToJson(g.settings))
                put("emulator", JSONObject().apply { g.emu.forEach { (k, v) -> put(k, v) } })
            })
        }
    }

    fun fromJson(array: JSONArray?): List<GameProfile> {
        if (array == null) return emptyList()
        val seen = mutableSetOf<String>()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val system = o.optString("system").trim()
            val file = o.optString("file")
            if (system.isEmpty() || file.isEmpty() || !seen.add(GameProfile.id(system, file))) return@mapNotNull null
            val emu = o.optJSONObject("emulator")?.let { e ->
                e.keys().asSequence().filter { EmuKnobs.byKey(it) != null }.associateWith { e.optString(it) }
            }.orEmpty()
            GameProfile(
                system = system,
                file = file,
                name = o.optString("name").ifBlank { file },
                path = o.optString("path").ifBlank { "./$file" },
                settings = ProfilesJson.settingsFromJson(o.optJSONObject("settings")),
                emu = emu,
            ).takeIf { !it.isEmpty }
        }
    }
}

/** A game ES-DE started, as its game-start hook wrote it down. */
data class GameEvent(
    /** Epoch seconds. */
    val time: Long,
    val system: String,
    /** The ROM's full path. */
    val rom: String,
    val name: String,
) {
    val file: String get() = rom.substringAfterLast('/').substringBeforeLast('.')
    val id: String get() = GameProfile.id(system, file)
}

/**
 * The link from ES-DE to Pocket Automator: ES-DE runs "custom event
 * scripts" as a game starts and ends. Two tiny scripts in ES-DE's
 * `scripts/game-start` and `scripts/game-end` folders call `on-game.sh` in
 * the "Pocket Automator" folder beside ES-DE's, which writes down the game
 * (`game.txt`) and swaps in a game's own Azahar resolution (Azahar has no
 * per-game settings on Android) and puts it back afterwards.
 *
 * Everything keeps working without the app, and everything can be undone
 * without it: deleting the "Pocket Automator" folder turns the scripts into
 * no-ops, and `Undo game settings.sh` there reverts every change.
 */
object Hooks {

    const val FOLDER = "Pocket Automator"
    const val STUB = "pocket-automator.sh"
    const val UNDO = "Undo game settings.sh"

    fun folder(home: File) = File(home.parentFile, FOLDER)

    fun stubs(home: File) = listOf(File(home, "scripts/game-start/$STUB"), File(home, "scripts/game-end/$STUB"))

    /** A path in single quotes for sh. */
    fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"

    /** The script ES-DE runs: [event] is "start" or "end". */
    fun stub(folder: File, event: String) = """
        |#!/bin/sh
        |# Added by Pocket Automator: tells it which game ES-DE starts, for per-game
        |# profiles. Delete this file, or the "$FOLDER" folder, to turn that off.
        |f=${quote(File(folder, "on-game.sh").path)}
        |[ -f "${'$'}f" ] && sh "${'$'}f" $event "${'$'}@"
        |exit 0
        |""".trimMargin()

    /**
     * The hook itself. ES-DE passes the ROM path (with spaces and the like
     * backslash-escaped), the game's name, the system and the system's full
     * name. [azaharConfig] is Azahar's config.ini, if Azahar is set up.
     */
    fun onGame(folder: File, azaharConfig: String?): String {
        val d = '$'
        return """
        |#!/bin/sh
        |# Pocket Automator's game hook, run by ES-DE as a game starts and ends.
        |# Pocket Automator rewrites this file; "$UNDO" undoes what it does.
        |D=${quote(folder.path)}
        |C=${quote(azaharConfig.orEmpty())}
        |M="${d}D/azahar-restore.txt"
        |ev=${d}1; shift
        |p=${d}(printf '%s' "${d}1" | sed 's/\\\(.\)/\1/g')
        |now() { sed -n 's/^resolution_factor *= *//p' "${d}C" | head -n 1; }
        |put() { sed -i "s/^resolution_factor *=.*/resolution_factor = ${d}1/" "${d}C"; }
        |# Puts Azahar's own resolution back, unless it was changed in Azahar meanwhile.
        |back() {
        |  [ -f "${d}M" ] && [ -f "${d}C" ] || return 0
        |  [ "${d}(now)" = "${d}(cut -f2 "${d}M")" ] && put "${d}(cut -f1 "${d}M")"
        |  return 0
        |}
        |if [ "${d}ev" = start ]; then
        |  printf '%s\n%s\n%s\n%s\n' "${d}(date +%s)" "${d}3" "${d}p" "${d}2" > "${d}D/game.txt"
        |  back; rm -f "${d}M"
        |  if [ -f "${d}C" ] && [ -f "${d}D/azahar.txt" ]; then
        |    v=${d}(awk -F '\t' -v n="${d}{p##*/}" '${d}1 == n { print ${d}2; exit }' "${d}D/azahar.txt")
        |    o=${d}(now)
        |    if [ -n "${d}v" ] && [ -n "${d}o" ] && [ "${d}v" != "${d}o" ]; then
        |      printf '%s\t%s\n' "${d}o" "${d}v" > "${d}M"; put "${d}v"
        |    fi
        |  fi
        |else
        |  back
        |fi
        |exit 0
        |""".trimMargin()
    }

    /** The last game ES-DE started, or null. */
    fun parseEvent(text: String?): GameEvent? {
        val lines = text?.lines() ?: return null
        if (lines.size < 4) return null
        val time = lines[0].trim().toLongOrNull() ?: return null
        val rom = lines[2].trim().ifEmpty { return null }
        return GameEvent(time, lines[1].trim(), rom, lines[3].trim())
    }

    fun readEvent(home: File): GameEvent? =
        parseEvent(runCatching { File(folder(home), "game.txt").takeIf { it.isFile }?.readText() }.getOrNull())

    /** Azahar's per-game resolutions for the hook: "ROM file name<TAB>factor" lines. */
    fun azaharList(games: List<GameProfile>): String =
        games.mapNotNull { g -> g.emu[EmuKnobs.AZAHAR_RESOLUTION.key]?.let { "${g.romName}\t$it" } }
            .sorted().joinToString("") { "$it\n" }

    /** Whether ES-DE runs custom event scripts. Blocking. */
    fun enabledInEsDe(home: File) = EsDe.setting(home, "CustomEventScripts") == "true"

    const val ESDE_PACKAGE = "org.es_de.frontend"

    /**
     * Turns on ES-DE's custom event scripts: ES-DE is stopped first (it
     * keeps its settings in memory and would write the old value back),
     * then its settings file is changed. It starts again with Home. Blocking.
     */
    fun enableInEsDe(home: File): Boolean {
        val file = File(home, "settings/es_settings.xml")
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        if (Shell.ready) Shell.run("am", "force-stop", ESDE_PACKAGE)
        val on = "<bool name=\"CustomEventScripts\" value=\"true\" />"
        val pattern = Regex("<bool name=\"CustomEventScripts\" value=\"\\w*\" />")
        val updated = if (pattern.containsMatchIn(text)) text.replace(pattern, on) else text.trimEnd() + "\n" + on + "\n"
        return runCatching { file.writeText(updated) }.isSuccess
    }

    fun installed(home: File) =stubs(home).all { it.isFile } && File(folder(home), "on-game.sh").isFile

    /** Writes the scripts (Pocket Automator has all-files access). Blocking. */
    fun install(home: File, azaharConfig: String?) {
        val folder = folder(home).apply { mkdirs() }
        writeIfChanged(File(folder, "on-game.sh"), onGame(folder, azaharConfig))
        val (start, end) = stubs(home)
        writeIfChanged(start, stub(folder, "start"))
        writeIfChanged(end, stub(folder, "end"))
    }

    fun remove(home: File) {
        stubs(home).forEach { it.delete() }
        File(folder(home), "on-game.sh").delete()
    }

    fun writeIfChanged(file: File, text: String) {
        if (runCatching { file.readText() }.getOrNull() == text) return
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
