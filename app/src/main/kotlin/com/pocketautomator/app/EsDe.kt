package com.pocketautomator.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Your games as ES-DE knows them: what you played last, your favorites, their
 * cover art, and which emulator runs each one, so the screens can show your
 * own games. Read-only: ES-DE's files are never written.
 *
 * ES-DE keeps a folder "ES-DE" (on internal storage or the SD card) with
 * `gamelists/<system>/gamelist.xml` and `downloaded_media/<system>/<type>/<rom name>.png`.
 */
object EsDe {

    data class Game(
        val system: String,
        val name: String,
        /** The ROM's file name without folder or extension: how ES-DE names its media. */
        val file: String,
        val favorite: Boolean,
        /** Epoch ms, or 0 if never played. */
        val lastPlayed: Long,
        val playCount: Int,
        /** Emulator label from ES-DE ("Azahar (Standalone)"), per game or per system. */
        val emulatorLabel: String?,
    )

    /** Parses one gamelist.xml. Plain text matching, so it runs anywhere (and in tests). */
    fun parse(system: String, xml: String): List<Game> {
        val systemLabel = Regex("<alternativeEmulator>\\s*<label>(.*?)</label>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim()
        return Regex("<game>(.*?)</game>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            fun tag(name: String) = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()
            val path = tag("path") ?: return@mapNotNull null
            val file = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { return@mapNotNull null }
            Game(
                system = system,
                name = tag("name") ?: file,
                file = file,
                favorite = tag("favorite") == "true",
                lastPlayed = tag("lastplayed")?.let(::time) ?: 0L,
                playCount = tag("playcount")?.toIntOrNull() ?: 0,
                emulatorLabel = tag("altemulator") ?: systemLabel,
            )
        }.toList()
    }

    private fun time(stamp: String): Long? =
        runCatching { SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).parse(stamp)?.time }.getOrNull()

    private fun unescape(text: String) = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Keywords in ES-DE's emulator labels → the apps that can be behind them, in order of preference. */
    private val LABELS = listOf(
        "azahar" to listOf("org.azahar_emu.azahar"),
        "lime3ds" to listOf("io.github.lime3ds.android"),
        "citra" to listOf("org.citra.emu", "org.azahar_emu.azahar"),
        "dolphin" to listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.mmjr"),
        "ppsspp" to listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
        "duckstation" to listOf("com.github.stenzek.duckstation"),
        "nethersx2" to listOf("xyz.aethersx2.android"),
        "aethersx2" to listOf("xyz.aethersx2.android"),
        "armsx2" to listOf("com.armsx2"),
        "melonds" to listOf("me.magnum.melonds", "me.magnum.melonds.nightly"),
        "drastic" to listOf("com.dsemu.drastic"),
        "flycast" to listOf("com.flycast.emulator"),
        "mupen64" to listOf("org.mupen64plusae.v3.fzurita"),
        "vita3k" to listOf("org.vita3k.emulator"),
        "eden" to SWITCH,
        "yuzu" to SWITCH,
        "citron" to SWITCH,
        "sudachi" to SWITCH,
        "suyu" to SWITCH,
        "retroarch" to RETROARCH,
    )

    private val SWITCH get() = listOf("dev.eden.eden_emulator", "io.github.citron_emu.citron", "org.sudachi.sudachi_emu", "org.yuzu.yuzu_emu")
    private val RETROARCH get() = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")

    /** Systems whose default emulator isn't RetroArch. */
    private val SYSTEM_DEFAULTS = mapOf(
        "gc" to listOf("org.dolphinemu.dolphinemu"),
        "wii" to listOf("org.dolphinemu.dolphinemu"),
        "n3ds" to listOf("org.azahar_emu.azahar", "io.github.lime3ds.android", "org.citra.emu"),
        "ps2" to listOf("xyz.aethersx2.android", "com.armsx2"),
        "psp" to listOf("org.ppsspp.ppsspp"),
        "psx" to listOf("com.github.stenzek.duckstation"),
        "nds" to listOf("me.magnum.melonds", "me.magnum.melonds.nightly", "com.dsemu.drastic"),
        "switch" to SWITCH,
        "psvita" to listOf("org.vita3k.emulator"),
    )

    /** Systems of Android apps rather than ROMs: no emulator behind them. */
    private val APP_SYSTEMS = setOf("androidapps", "androidgames", "emulators", "windows", "desktop", "steam")

    /** The installed app that runs [game], if there is one. */
    fun emulatorFor(game: Game, installed: Set<String>): String? {
        if (game.system in APP_SYSTEMS) return null
        val label = game.emulatorLabel?.lowercase()
        val fromLabel = label?.let { l ->
            // A label without "standalone" is a RetroArch core ("mGBA", "Beetle PSX HW").
            if ("standalone" !in l) RETROARCH
            else LABELS.firstOrNull { (key, _) -> key in l }?.second
        }
        val choices = fromLabel ?: SYSTEM_DEFAULTS[game.system] ?: RETROARCH
        return choices.firstOrNull { it in installed }
    }

    /** ES-DE's folder: the one with gamelists in it, on internal storage or an SD card. */
    fun home(): File? {
        val candidates = listOf(File("/sdcard/ES-DE")) +
            (File("/storage").listFiles()?.filter { it.name != "self" && it.name != "emulated" }?.map { File(it, "ES-DE") }.orEmpty())
        return candidates.firstOrNull { File(it, "gamelists").listFiles()?.isNotEmpty() == true }
    }

    /** Cover art first, then other pictures ES-DE downloaded. */
    private val COVER_TYPES = listOf("covers", "miximages", "3dboxes", "screenshots", "titlescreens")
    private val WIDE_TYPES = listOf("fanart", "screenshots", "titlescreens", "miximages", "covers")

    fun cover(home: File, game: Game): File? = media(home, game, COVER_TYPES)

    /** A wide picture for banners: fan art or a screenshot. */
    fun wide(home: File, game: Game): File? = media(home, game, WIDE_TYPES)

    private fun media(home: File, game: Game, types: List<String>): File? {
        for (type in types) {
            for (ext in listOf("png", "jpg", "jpeg", "webp")) {
                val f = File(home, "downloaded_media/${game.system}/$type/${game.file}.$ext")
                if (f.isFile) return f
            }
        }
        return null
    }

    /** Every game in every gamelist. Blocking: reads files. */
    fun load(home: File): List<Game> =
        File(home, "gamelists").listFiles().orEmpty().filter { it.isDirectory }.flatMap { dir ->
            val xml = File(dir, "gamelist.xml").takeIf { it.isFile } ?: return@flatMap emptyList()
            runCatching { parse(dir.name, xml.readText()) }.getOrDefault(emptyList())
        }

    /** ES-DE's lists of apps and emulators launched from it: not games, so not shown as games. */
    private val NOT_GAMES = setOf("androidapps", "emulators", "desktop")

    fun isGame(game: Game): Boolean = game.system !in NOT_GAMES

    fun recent(games: List<Game>, count: Int): List<Game> =
        games.filter { it.lastPlayed > 0 && isGame(it) }.sortedByDescending { it.lastPlayed }.take(count)

    fun favorites(games: List<Game>): List<Game> =
        games.filter { it.favorite && isGame(it) }.sortedWith(compareByDescending<Game> { it.lastPlayed }.thenBy { it.name.lowercase() })
}
