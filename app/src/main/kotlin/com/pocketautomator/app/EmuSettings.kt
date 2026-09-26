package com.pocketautomator.app

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** The emulators whose settings Pocket Automator can set per game. */
enum class Emulator(val pkg: String, val title: String) {
    EDEN("dev.eden.eden_emulator", "Eden"),
    DOLPHIN("org.dolphinemu.dolphinemu", "Dolphin"),
    AZAHAR("org.azahar_emu.azahar", "Azahar");

    companion object {
        fun of(pkg: String?): Emulator? = entries.firstOrNull { it.pkg == pkg }
    }
}

data class EmuOption(val value: String, val label: String)

/**
 * One emulator setting a game can have its own value for. [section] and
 * [name] say where it lives in the emulator's ini file.
 */
data class EmuKnob(
    val key: String,
    val emulator: Emulator,
    val title: String,
    val section: String,
    val name: String,
    val options: List<EmuOption>,
) {
    fun label(value: String?): String? = options.firstOrNull { it.value == value }?.label
}

object EmuKnobs {

    // Eden's ResolutionSetup: 0 = ¼×, 1 = ½×, 2 = ¾×, 3 = 1× (720p), 4 = 1¼×, 5 = 1½× (1080p), 6 = 2×.
    val EDEN_RESOLUTION = EmuKnob(
        "eden.resolution", Emulator.EDEN, "Resolution", "Renderer", "resolution_setup",
        listOf(
            EmuOption("1", "½× · 360p"),
            EmuOption("2", "¾× · 540p"),
            EmuOption("3", "1× · 720p"),
            EmuOption("5", "1½× · 1080p"),
            EmuOption("6", "2× · 1440p"),
        ),
    )
    val EDEN_ACCURACY = EmuKnob(
        "eden.accuracy", Emulator.EDEN, "GPU accuracy", "Renderer", "gpu_accuracy",
        listOf(EmuOption("0", "Low · faster"), EmuOption("1", "High · fewer glitches")),
    )

    /** Its options are the drivers installed in Eden (see [edenDriver]). */
    val EDEN_DRIVER = EmuKnob("eden.driver", Emulator.EDEN, "GPU driver", "GpuDriver", "driver_path", emptyList())

    val DOLPHIN_RESOLUTION = EmuKnob(
        "dolphin.resolution", Emulator.DOLPHIN, "Resolution", "Video_Settings", "InternalResolution",
        listOf(
            EmuOption("1", "1× · 480p"),
            EmuOption("2", "2× · for 720p"),
            EmuOption("3", "3× · for 1080p"),
            EmuOption("4", "4× · for 1440p"),
        ),
    )

    // Azahar's resolution_factor: multiples of the 3DS's 400×240.
    val AZAHAR_RESOLUTION = EmuKnob(
        "azahar.resolution", Emulator.AZAHAR, "Resolution", "Renderer", "resolution_factor",
        listOf(
            EmuOption("1", "1× · 240p"),
            EmuOption("2", "2× · 480p"),
            EmuOption("3", "3× · 720p"),
            EmuOption("4", "4× · 960p"),
            EmuOption("5", "5× · for 1080p"),
        ),
    )

    val all = listOf(EDEN_RESOLUTION, EDEN_ACCURACY, EDEN_DRIVER, DOLPHIN_RESOLUTION, AZAHAR_RESOLUTION)

    fun byKey(key: String) = all.firstOrNull { it.key == key }

    fun of(emulator: Emulator) = all.filter { it.emulator == emulator }

    /** The driver choice with Eden's installed drivers ([paths]) as options, plus the system's own. */
    fun edenDriver(paths: List<String>) = EDEN_DRIVER.copy(
        options = listOf(EmuOption("", "System driver")) + paths.map { path ->
            EmuOption(path, path.substringAfterLast('/').removeSuffix(".zip").removeSuffix(".adpkg"))
        },
    )
}

/**
 * Editing emulator ini files a line at a time, so everything else in them
 * (and the emulator's own layout) stays as it was.
 *
 * Eden (a Qt ini) keeps a per-game value as three lines,
 * `name\use_global=false`, `name\default=false` and `name=value`;
 * `name\use_global=true` alone means "same as the global setting".
 * Dolphin writes `Name = value`.
 */
object Ini {

    /** [section]'s lines as a range in [lines]: from its header to the next one. */
    private fun range(lines: List<String>, section: String): IntRange? {
        val start = lines.indexOfFirst { it.trim() == "[$section]" }
        if (start < 0) return null
        val next = (start + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("[") } ?: lines.size
        return start until next
    }

    private fun keyOf(line: String): String = line.substringBefore('=').trim()

    /** The value of [name] in [section], if it's set there. */
    fun get(text: String, section: String, name: String): String? {
        val lines = text.lines()
        val r = range(lines, section) ?: return null
        return r.drop(1).map { lines[it] }.firstOrNull { keyOf(it) == name }?.substringAfter('=')?.trim()
    }

    /** Eden: whether [name] has a per-game value of its own. */
    fun edenCustom(text: String, section: String, name: String): Boolean =
        get(text, section, "$name\\use_global") == "false"

    /** Eden: [text] with [name] set to [value] for this game. */
    fun edenSet(text: String, section: String, name: String, value: String): String {
        val block = listOf("$name\\use_global=false", "$name\\default=false", "$name=$value")
        return replaceKey(text, section, setOf(name, "$name\\use_global", "$name\\default"), block)
    }

    /** Eden: [text] with [name] back to the global setting. */
    fun edenClear(text: String, section: String, name: String): String =
        replaceKey(text, section, setOf(name, "$name\\use_global", "$name\\default"), listOf("$name\\use_global=true"))

    /** Dolphin: [text] with `Name = value` in [section]. */
    fun dolphinSet(text: String, section: String, name: String, value: String): String =
        replaceKey(text, section, setOf(name), listOf("$name = $value"))

    /** Dolphin: [text] without [name]; an emptied section goes too. */
    fun dolphinRemove(text: String, section: String, name: String): String {
        val out = replaceKey(text, section, setOf(name), emptyList())
        val lines = out.lines()
        val r = range(lines, section) ?: return out
        if (r.drop(1).any { lines[it].isNotBlank() }) return out
        return lines.filterIndexed { i, _ -> i !in r }.joinToString("\n").trim('\n').let { if (it.isEmpty()) "" else "$it\n" }
    }

    /**
     * [text] with the lines for [keys] in [section] swapped for [block], at
     * the first of them, or at the end of the section (a new one if need be).
     */
    private fun replaceKey(text: String, section: String, keys: Set<String>, block: List<String>): String {
        val lines = text.lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.size - 1)
        val r = range(lines, section)
        if (r == null) {
            if (block.isEmpty()) return text
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$section]"
            lines += block
            return lines.joinToString("\n") + "\n"
        }
        val hits = r.drop(1).filter { keyOf(lines[it]) in keys }
        val at = hits.firstOrNull() ?: (r.drop(1).lastOrNull { lines[it].isNotBlank() }?.plus(1) ?: (r.first + 1))
        hits.reversed().forEach { lines.removeAt(it) }
        lines.addAll(at.coerceAtMost(lines.size), block)
        return lines.joinToString("\n") + "\n"
    }
}

/** Reading a game's ID out of its ROM, for the emulators that name per-game files by it. */
object GameIds {

    private val TITLE_ID = Regex("\\b(01[0-9A-Fa-f]{14})\\b")

    /** A Switch title ID in a file name ("Game [0100ABCD12340000].nsp"), as the base game's. */
    fun switchFromName(name: String): String? = TITLE_ID.find(name)?.groupValues?.get(1)?.let(::base)

    /** The base game's title ID: an update's (…800) or DLC's has other low bits. */
    fun base(id: String): String = id.uppercase().dropLast(3) + "000"

    /**
     * A Switch title ID from an NSP: its tickets are named by rights ID, which
     * starts with the title ID. XCIs have no tickets, so they're learned by play.
     */
    fun switchFromNsp(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(16).also { f.readFully(it) }
            if (String(head, 0, 4, Charsets.US_ASCII) != "PFS0") return null
            val count = le32(head, 4)
            val tableSize = le32(head, 8)
            if (count !in 1..4096 || tableSize !in 1..1_000_000) return null
            val table = ByteArray(tableSize).also { f.seek(16L + count * 24L); f.readFully(it) }
            String(table, Charsets.US_ASCII).split('\u0000')
                .firstOrNull { it.endsWith(".tik", ignoreCase = true) && it.length >= 20 }
                ?.take(16)?.takeIf { TITLE_ID.matches(it) }?.let(::base)
        }
    }.getOrNull()

    /** A GameCube/Wii game ID ("GM4E01") from an ISO, GCM, WBFS, CISO, RVZ or WIA file. */
    fun dolphin(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val magic = ByteArray(4).also { f.readFully(it) }
            val at = when (String(magic, Charsets.US_ASCII)) {
                "RVZ\u0001", "WIA\u0001" -> 0x58L
                "WBFS" -> 0x200L
                "CISO" -> 0x8000L
                else -> 0L
            }
            val id = ByteArray(6).also { f.seek(at); f.readFully(it) }
            String(id, Charsets.US_ASCII).takeIf { it.matches(Regex("[A-Z0-9]{6}")) }
        }
    }.getOrNull()

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Eden's cache/launched.json: title ID → when it was last started (epoch seconds). */
    fun edenLaunches(json: String?): Map<String, Long> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associate { k -> k.uppercase() to (o.optJSONObject(k)?.optLong("timestamp") ?: 0L) }
    }.getOrDefault(emptyMap())

    /**
     * [file]'s title ID from its last session, for games played before
     * Pocket Automator watched: ES-DE writes a game's "last played" as you
     * come back from it ([ended], epoch seconds by ROM file), and Eden notes
     * each title's latest start ([launches]). The session's start is the
     * latest Eden start before it ended, within 8 hours, as long as no other
     * game from ES-DE ended in between and the title isn't another game's
     * already ([taken]).
     */
    fun fromSessions(file: String, ended: Map<String, Long>, launches: Map<String, Long>, taken: Set<String>): String? {
        val end = ended[file]?.takeIf { it > 0 } ?: return null
        val (id, start) = launches.filter { (_, t) -> t <= end + 60 }.maxByOrNull { it.value } ?: return null
        if (end - start > 8 * 3600) return null
        if (ended.any { (other, t) -> other != file && t in (start + 1) until end }) return null
        return base(id).takeIf { it !in taken }
    }

    /**
     * The title just started: the one launched at [since] or up to two
     * minutes after (epoch seconds), and the latest of them.
     */
    fun startedAfter(launches: Map<String, Long>, since: Long): String? =
        launches.filter { (_, t) -> t in (since - 5)..(since + 120) }.maxByOrNull { it.value }?.key?.let(::base)
}
