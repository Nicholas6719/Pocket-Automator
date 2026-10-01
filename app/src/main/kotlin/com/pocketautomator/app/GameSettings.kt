package com.pocketautomator.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puts games' own emulator settings into the emulators' files, and takes
 * them out again, so the files always match the game profiles:
 *
 * - **Eden**: `config/custom/<title ID>.ini`, the file Eden's own per-game
 *   settings use. The title ID comes from the NSP, the file name, or is
 *   learned the first time the game starts from ES-DE.
 * - **Dolphin**: `GameSettings/<game ID>.ini`, the file Dolphin's own
 *   per-game settings use; the game ID is read from the disc image.
 * - **Azahar**: it has no per-game settings on Android, so the ES-DE hook
 *   ([Hooks]) swaps the resolution in as the game starts and puts it back after.
 *
 * Never over yours: a setting you've given a game in the emulator itself is
 * left alone, and Pocket Automator only ever takes back what it wrote.
 * Before a file is first changed, a copy goes to "Pocket Automator/backups";
 * "Pocket Automator/Undo game settings.sh" takes everything back without the app.
 *
 * Eden's and Dolphin's files are in Android/data, so they're read and
 * written through Shizuku. Blocking: everything runs on one worker.
 */
object GameSettings {

    const val EDEN_FILES = "/storage/emulated/0/Android/data/dev.eden.eden_emulator/files"
    private val DOLPHIN_DIRS = listOf(
        "/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files",
        "/storage/emulated/0/dolphin-emu",
    )

    /** A value Pocket Automator wrote: taken back only while the file still has it. */
    data class Applied(val game: String, val knob: String, val file: String, val value: String)

    private val worker = Executors.newSingleThreadExecutor()
    private val pending = AtomicBoolean(false)

    /** Brings the files in line with the game profiles, soon; calls close together run once. */
    fun requestSync(context: Context) {
        if (!pending.compareAndSet(false, true)) return
        val app = context.applicationContext
        worker.execute {
            pending.set(false)
            runCatching { sync(app) }.onFailure { Store.get(app).log("game settings: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    fun onWorker(block: () -> Unit) = worker.execute { runCatching(block) }

    private fun sync(context: Context) {
        val store = Store.get(context)
        val home = EsDe.home()
        val status = mutableMapOf<String, String>()
        val games = wanted(context, home)
        val azahar = store.azaharConfig ?: home?.let { findAzaharConfig() }?.also { store.azaharConfig = it }

        if (home != null) {
            val folder = Hooks.folder(home)
            if (store.gameDetection.value) Hooks.install(home, azahar) else Hooks.remove(home)
            Hooks.writeIfChanged(File(folder, "azahar.txt"), Hooks.azaharList(games))
            if (azahar == null) {
                games.forEach { (g, emu) ->
                    emu.keys.filter { it.startsWith("azahar.") }.forEach {
                        status["${g.id}|$it"] = "Azahar's settings folder wasn't found, so this can't be applied yet."
                    }
                }
            }
        }

        if (Shell.ready) {
            reconcileFiles(context, home, games, status)
        } else if (games.values.any { emu -> emu.keys.any { !it.startsWith("azahar.") } }) {
            store.log("game settings wait for Shizuku")
        }
        store.setGameStatus(status)
        if (home != null) writeUndo(home, store.applied, azahar, store.retroArchFiles)
    }

    /**
     * Every game's emulator settings as they should be: yours, and the
     * community suggestions used automatically for games you have (see
     * [Suggestions.effective]).
     */
    private fun wanted(context: Context, home: File?): Map<GameProfile, Map<String, String>> {
        val store = Store.get(context)
        val auto = store.suggestAuto.value
        val mine = store.games.value
        val suggested = Suggestions.byKey(context)
        val runs = Runs(context, home)
        val out = linkedMapOf<GameProfile, Map<String, String>>()
        for (game in mine) {
            val runner = runs.runner(game.system, game.file)
            val own = EmuKnobs.forEmulator(game.emu, runner?.emulator)
            val emu = only(Suggestions.effective(own, suggested[Suggestions.key(game.system, game.name)], auto, Suggestions.sameChip), runner)
            if (emu.isNotEmpty()) out[game] = emu
        }
        // Games of yours the community has settings for, found in ES-DE's lists by name.
        if (auto && home != null) {
            val taken = mine.map { it.id }.toSet()
            val systems = suggested.values.filter { s -> s.settings.any { Suggestions.used(it) } }.map { it.system }.toSet()
            val romDir = EsDe.romDir(home)
            for (system in systems) {
                val list = runCatching { EsDe.parse(system, File(home, "gamelists/$system/gamelist.xml").readText()) }.getOrNull() ?: continue
                for (g in list) {
                    val s = suggested[Suggestions.key(system, g.name)] ?: continue
                    if (g.id in taken || s.settings.none { Suggestions.used(it) }) continue
                    if (!File(romDir, system + "/" + g.path.removePrefix("./")).isFile) continue
                    val emu = only(Suggestions.effective(null, s, true, Suggestions.sameChip), runs.runner(g))
                    if (emu.isNotEmpty()) out[GameProfile.of(g)] = emu
                }
            }
        }
        return out
    }

    /**
     * [emu] with only the settings for the emulator that runs the game: none
     * when it's one Pocket Automator can't set, all when it isn't known.
     */
    private fun only(emu: Map<String, String>, runner: Runner?): Map<String, String> = when {
        runner == null -> emu
        runner.emulator == null -> emptyMap()
        else -> emu.filterKeys { EmuKnobs.byKey(it)?.emulator == runner.emulator }
    }

    /** A game's emulator as ES-DE starts it; null [emulator] for one whose settings Pocket Automator can't set. */
    private data class Runner(val emulator: Emulator?)

    /**
     * Which emulator runs each game, as ES-DE starts it: its emulator label
     * (per game or per system) and the apps installed. Gamelists are read once per sync.
     */
    private class Runs(context: Context, private val home: File?) {
        private val installed: Set<String> by lazy {
            runCatching { context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet() }.getOrDefault(emptySet())
        }
        private val lists = mutableMapOf<String, List<EsDe.Game>>()

        /** Null when the apps can't be listed. */
        fun runner(game: EsDe.Game): Runner? =
            if (installed.isEmpty()) null
            else Runner(Emulator.forGame(EsDe.emulatorFor(game, installed), game.emulatorLabel, game.system))

        /** Null when ES-DE or the game isn't found. */
        fun runner(system: String, file: String): Runner? {
            val home = home ?: return null
            val list = lists.getOrPut(system) {
                runCatching { EsDe.parse(system, File(home, "gamelists/$system/gamelist.xml").readText()) }.getOrDefault(emptyList())
            }
            return list.firstOrNull { it.file == file }?.let(::runner)
        }
    }

    /** Eden's and Dolphin's per-game files. */
    private fun reconcileFiles(context: Context, home: File?, games: Map<GameProfile, Map<String, String>>, status: MutableMap<String, String>) {
        val store = Store.get(context)
        data class Want(val game: GameProfile, val knob: EmuKnob, val value: String)

        val wanted = mutableMapOf<Pair<String, String>, Want>()
        val retroArch = retroArchSetup()
        // RetroArch: per game options file → the core and the game's own options.
        val raWanted = mutableMapOf<String, Pair<GameProfile, MutableMap<EmuKnob, String>>>()
        for ((g, emu) in games) {
            for ((key, value) in emu) {
                val knob = EmuKnobs.byKey(key) ?: continue
                if (knob.emulator == Emulator.AZAHAR) continue
                if (knob.emulator.isRetroArch) {
                    when {
                        retroArch == null -> status["${g.id}|$key"] = "RetroArch's settings couldn't be read."
                        !retroArch.gameOptions -> status["${g.id}|$key"] = "Turn on RetroArch's game-specific core options (Settings → Core) for this to apply."
                        else -> raWanted.getOrPut(retroArch.gameFile(knob.emulator, g.romName)) { g to mutableMapOf() }.second[knob] = value
                    }
                    continue
                }
                val targets = when (knob.emulator) {
                    Emulator.EDEN -> listOfNotNull(edenId(context, home, g)?.let { "$EDEN_FILES/config/custom/$it.ini" })
                        .ifEmpty { status["${g.id}|$key"] = "Start it once from ES-DE, and Pocket Automator will learn Eden's ID for it."; emptyList() }
                    Emulator.DOLPHIN -> listOfNotNull(dolphinFile(home, g))
                        .ifEmpty { status["${g.id}|$key"] = "Couldn't read the game's ID from its disc image."; emptyList() }
                    Emulator.ARMSX2 -> armsx2Files(context, g)
                        .ifEmpty { status["${g.id}|$key"] = "Pocket Automator doesn't know this game's PS2 ID yet: start it once from ES-DE."; emptyList() }
                    Emulator.DUCKSTATION -> listOfNotNull(duckstationFile(context, g))
                        .ifEmpty { status["${g.id}|$key"] = "Pocket Automator doesn't know this game's PS1 serial."; emptyList() }
                    Emulator.AZAHAR, Emulator.SWANSTATION, Emulator.MUPEN64, Emulator.FLYCAST -> emptyList()
                }
                // PS2 games can have a file per disc revision: each gets the setting, the right one is used.
                for (file in targets) wanted[file to key] = Want(g, knob, value)
            }
        }

        val applied = store.applied.toMutableList()
        reconcileRetroArch(store, home, retroArch, raWanted, applied, status)
        val raFiles = applied.filter { EmuKnobs.byKey(it.knob)?.emulator?.isRetroArch == true }.map { it.file }.toSet()
        val files = (wanted.keys.map { it.first } + applied.map { it.file }).distinct().filter { it !in raFiles }
        for (file in files) {
            val original = read(file)
            var text = original.orEmpty()
            val mine = applied.filter { it.file == file }
            // Take back what's no longer wanted; anything changed since is yours now.
            for (a in mine) {
                val knob = EmuKnobs.byKey(a.knob)
                if (knob == null || value(text, knob) != a.value) {
                    applied.remove(a)
                    continue
                }
                if (wanted[file to a.knob] == null) {
                    text = clear(text, knob)
                    applied.remove(a)
                }
            }
            for ((where, want) in wanted) {
                if (where.first != file) continue
                val current = value(text, want.knob)
                val ours = applied.firstOrNull { it.file == file && it.knob == want.knob.key }
                val statusKey = "${want.game.id}|${want.knob.key}"
                if (ours == null && current != null) {
                    // Set in the emulator itself: that one stays.
                    if (current != want.value) {
                        status[statusKey] = "Set in ${want.knob.emulator.title} itself (${want.knob.label(current) ?: current}), so Pocket Automator leaves it alone."
                    }
                    continue
                }
                if (current != want.value) text = set(text, want.knob, want.value)
                applied.remove(ours)
                applied += Applied(want.game.id, want.knob.key, file, want.value)
            }
            if (text != original.orEmpty()) {
                val emulator = wanted.entries.firstOrNull { it.key.first == file }?.value?.knob?.emulator
                    ?: store.applied.firstNotNullOfOrNull { a -> EmuKnobs.byKey(a.knob)?.emulator?.takeIf { a.file == file } }
                if (original != null && home != null) backup(home, file, emulator?.title ?: "Other")
                if (!write(file, text)) {
                    store.log("couldn't write $file")
                    applied.removeAll { it.file == file && it !in store.applied }
                    wanted.filterKeys { it.first == file }.values.forEach { status["${it.game.id}|${it.knob.key}"] = "Couldn't write ${it.knob.emulator.title}'s file." }
                } else {
                    store.log("game settings: wrote ${file.substringAfterLast('/')}")
                }
            }
        }
        store.applied = applied
    }

    /** RetroArch's setup from the installed RetroArch's retroarch.cfg; null without RetroArch (or Shizuku). */
    private fun retroArchSetup(): RetroArch.Setup? {
        for (pkg in RetroArch.PACKAGES) {
            val path = RetroArch.configPath(pkg)
            val cfg = read(path) ?: continue
            return RetroArch.setup(cfg, path)
        }
        return null
    }

    private fun isRetroArch(a: Applied) = EmuKnobs.byKey(a.knob)?.emulator?.isRetroArch == true

    /**
     * RetroArch's per-game options files (see [RetroArch]). A file Pocket
     * Automator made, still as it wrote it, is kept equal to the core's
     * options now with the game's on top, and goes once nothing is wanted in
     * it. A file made or changed in RetroArch is yours: only the options
     * Pocket Automator set in it are touched, and a value you set there wins.
     */
    private fun reconcileRetroArch(
        store: Store,
        home: File?,
        setup: RetroArch.Setup?,
        wanted: Map<String, Pair<GameProfile, Map<EmuKnob, String>>>,
        applied: MutableList<Applied>,
        status: MutableMap<String, String>,
    ) {
        // Without RetroArch's settings nothing is known about its files: they're left as they are.
        if (setup == null) return
        val written = store.retroArchFiles.toMutableMap()
        val bases = mutableMapOf<Emulator, Map<String, String>>()
        fun base(emulator: Emulator) = bases.getOrPut(emulator) { RetroArch.base(setup, emulator, ::read) }
        val files = (wanted.keys + applied.filter(::isRetroArch).map { it.file } + written.keys).distinct()
        for (file in files) {
            val want = wanted[file]
            val mine = applied.filter { it.file == file && isRetroArch(it) }
            val emulator = want?.second?.keys?.firstOrNull()?.emulator
                ?: mine.firstNotNullOfOrNull { EmuKnobs.byKey(it.knob)?.emulator }
                ?: Emulator.entries.firstOrNull { it.core == file.substringBeforeLast('/').substringAfterLast('/') }
                ?: continue
            val current = read(file)?.let(RetroArch::parse)
            val ours = written[file]
            if (current == null) {
                // Never made, or deleted by hand.
                written.remove(file)
                applied.removeAll(mine)
                if (want == null) continue
            }
            if (current == null || (ours != null && RetroArch.unchanged(current, ours))) {
                applied.removeAll(mine)
                if (want == null) {
                    if (write(file, "")) {
                        written.remove(file)
                        store.log("game settings: removed ${file.substringAfterLast('/')}")
                    }
                    continue
                }
                val options = LinkedHashMap(base(emulator))
                want.second.forEach { (knob, value) -> options[knob.name] = value }
                if (current != options) {
                    if (!write(file, RetroArch.render(options))) {
                        store.log("couldn't write $file")
                        want.second.keys.forEach { status["${want.first.id}|${it.key}"] = "Couldn't write RetroArch's options file." }
                        continue
                    }
                    store.log("game settings: wrote ${file.substringAfterLast('/')}")
                }
                written[file] = options
                want.second.forEach { (knob, value) -> applied += Applied(want.first.id, knob.key, file, value) }
                continue
            }
            // Made or changed in RetroArch: only Pocket Automator's own options in it change.
            written.remove(file)
            val updated = LinkedHashMap(current)
            for (a in mine) {
                val knob = EmuKnobs.byKey(a.knob) ?: continue
                if (current[knob.name] != a.value) {
                    applied.remove(a)
                } else if (want?.second?.keys?.none { it.key == a.knob } != false) {
                    base(emulator)[knob.name]?.let { updated[knob.name] = it } ?: updated.remove(knob.name)
                    applied.remove(a)
                }
            }
            want?.second?.forEach { (knob, value) ->
                val previous = applied.firstOrNull { it.file == file && it.knob == knob.key }
                val now = updated[knob.name]
                if (previous == null && now != null) {
                    if (now != value) {
                        status["${want.first.id}|${knob.key}"] = "Set in RetroArch itself for this game (${knob.label(now) ?: now}), so Pocket Automator leaves it alone."
                    }
                    return@forEach
                }
                updated[knob.name] = value
                applied.remove(previous)
                applied += Applied(want.first.id, knob.key, file, value)
            }
            if (updated != current) {
                if (home != null) backup(home, file, "RetroArch")
                if (write(file, RetroArch.render(updated))) {
                    store.log("game settings: wrote ${file.substringAfterLast('/')}")
                } else {
                    store.log("couldn't write $file")
                    applied.removeAll { it.file == file && it !in store.applied }
                }
            }
        }
        store.retroArchFiles = written
    }

    /** The game's own value in [text], or null if it follows the emulator's setting. */
    fun value(text: String, knob: EmuKnob): String? = when (knob.emulator) {
        Emulator.EDEN -> if (Ini.edenCustom(text, knob.section, knob.name)) Ini.get(text, knob.section, knob.name) else null
        else -> Ini.get(text, knob.section, knob.name)
    }

    private fun set(text: String, knob: EmuKnob, value: String) = when (knob.emulator) {
        Emulator.EDEN -> Ini.edenSet(text, knob.section, knob.name, value)
        else -> Ini.dolphinSet(text, knob.section, knob.name, value)
    }

    private fun clear(text: String, knob: EmuKnob) = when (knob.emulator) {
        Emulator.EDEN -> Ini.edenClear(text, knob.section, knob.name)
        else -> Ini.dolphinRemove(text, knob.section, knob.name)
    }

    /** Eden's title ID for [game]: known, from the NSP or file name, or matched from play history. */
    private fun edenId(context: Context, home: File?, game: GameProfile): String? {
        val store = Store.get(context)
        val rom = home?.let { File(EsDe.romDir(it), game.system + "/" + game.path.removePrefix("./")) }
        // Read from the cart itself when possible: surer than anything else.
        val fromCart = rom?.let { cartIds.getOrPut(it.path) { edenHeaderKey()?.let { key -> GameIds.switchFromCart(it, key) } } }
        if (fromCart != null) {
            if (store.edenIds[game.id] != fromCart) store.setEdenId(game.id, fromCart)
            return fromCart
        }
        store.edenIds[game.id]?.let { return it }
        val id = GameIds.switchFromName(game.romName)
            ?: rom?.takeIf { it.name.endsWith(".nsp", true) }?.let(GameIds::switchFromNsp)
            ?: Suggestions.find(context, game.system, game.name)?.gameId?.takeIf { GameIds.switchFromName("[$it]") != null }?.let(GameIds::base)
            ?: home?.let { fromHistory(it, game, store.edenIds.values.toSet()) }
        if (id != null) store.setEdenId(game.id, id)
        return id
    }

    private val cartIds = mutableMapOf<String, String?>()
    private var headerKey: ByteArray? = null

    /** Eden's NCA header key from its prod.keys (read once; never stored). */
    private fun edenHeaderKey(): ByteArray? =
        headerKey ?: GameIds.headerKey(read("$EDEN_FILES/keys/prod.keys"))?.also { headerKey = it }

    /** [game]'s title ID matched from its last session: see [GameIds.fromSessions]. */
    private fun fromHistory(home: File, game: GameProfile, taken: Set<String>): String? {
        val games = runCatching { EsDe.parse(game.system, File(home, "gamelists/${game.system}/gamelist.xml").readText()) }
            .getOrNull() ?: return null
        val ended = games.associate { it.file to it.lastPlayed / 1000 }
        val launches = GameIds.edenLaunches(read("$EDEN_FILES/cache/launched.json"))
        return GameIds.fromSessions(game.file, ended, launches, taken)
    }

    /** After ES-DE started [event] in Eden: learns its title ID from Eden's launch record. */
    fun learnEdenId(context: Context, event: GameEvent) = worker.execute {
        val store = Store.get(context)
        if (!Shell.ready) return@execute
        val id = GameIds.startedAfter(GameIds.edenLaunches(read("$EDEN_FILES/cache/launched.json")), event.time) ?: return@execute
        // Seen starting: surer than a match from history, so it wins.
        if (store.edenIds[event.id] == id) return@execute
        store.setEdenId(event.id, id)
        store.log("learned Eden's ID for ${event.name}: $id")
        if (store.game(event.id)?.emu?.keys?.any { it.startsWith("eden.") } == true) requestSync(context)
    }

    const val DUCKSTATION_FILES = "/storage/emulated/0/Android/data/com.github.stenzek.duckstation/files"

    /**
     * ARMSX2's per-game files for [game], one per known serial and CRC
     * (PCSX2 names them <serial>_<CRC>.ini). The ID comes from the bundled
     * table, or from ARMSX2's recent games (the serial) and its patch
     * archive (the CRCs for that serial).
     */
    private fun armsx2Files(context: Context, game: GameProfile): List<String> {
        val root = armsx2Root() ?: return emptyList()
        val ids = mutableSetOf<String>()
        Suggestions.find(context, game.system, game.name)?.gameId?.split(',')?.map { it.trim() }?.filter { it.contains('_') }?.let(ids::addAll)
        val recent = runCatching { File(root, "recent_games.json").readText() }.getOrNull()
        GameIds.ps2SerialFromRecent(recent, game.romName)?.let { serial ->
            ids += armsx2Crcs(root)[serial].orEmpty().map { "${serial}_$it" }
        }
        return ids.sorted().map { "$root/gamesettings/$it.ini" }
    }

    private var ps2Crcs: Map<String, List<String>>? = null

    /** Serial → CRCs, from the names in ARMSX2's patches.zip ("SLUS-21921_8A1D18EE.pnach"). */
    private fun armsx2Crcs(root: File): Map<String, List<String>> = ps2Crcs ?: runCatching {
        java.util.zip.ZipFile(File(root, "resources/patches.zip")).use { zip ->
            zip.entries().asSequence().mapNotNull { Regex("([A-Z]{4}-\\d{5})_([0-9A-F]{8})\\.pnach").matchEntire(it.name.substringAfterLast('/')) }
                .groupBy({ it.groupValues[1] }, { it.groupValues[2] })
        }
    }.getOrDefault(emptyMap()).also { ps2Crcs = it }

    private var armsx2RootCache: File? = null

    /** ARMSX2's data folder (picked in ARMSX2): the one with its PCSX2-Android.ini. */
    fun armsx2Root(): File? = armsx2RootCache?.takeIf { File(it, "PCSX2-Android.ini").isFile } ?: run {
        val roots = listOf(File("/storage/emulated/0")) +
            File("/storage").listFiles().orEmpty().filter { it.name != "self" && it.name != "emulated" }
        roots.asSequence().flatMap { root ->
            val level1 = root.listFiles().orEmpty().filter { it.isDirectory && it.name != "Android" }
            (level1 + level1.flatMap { d -> d.listFiles().orEmpty().filter { it.isDirectory } }).asSequence()
        }.firstOrNull { File(it, "PCSX2-Android.ini").isFile }?.also { armsx2RootCache = it }
    }

    /** DuckStation's per-game file for [game], named by its serial. */
    private fun duckstationFile(context: Context, game: GameProfile): String? =
        Suggestions.find(context, game.system, game.name)?.gameId?.takeIf { it.matches(Regex("[A-Z]{4}-\\d{5}")) }
            ?.let { "$DUCKSTATION_FILES/gamesettings/$it.ini" }

    private val dolphinIds = mutableMapOf<String, String?>()

    private fun dolphinFile(home: File?, game: GameProfile): String? {
        val dir = dolphinDir() ?: return null
        val id = dolphinIds.getOrPut(game.id) {
            home?.let { GameIds.dolphin(File(EsDe.romDir(it), game.system + "/" + game.path.removePrefix("./"))) }
        } ?: return null
        return "$dir/GameSettings/$id.ini"
    }

    private fun dolphinDir(): String? =
        Shell.sh("for d in \"$@\"; do [ -d \"\$d/Config\" ] && echo \"\$d\" && break; done", *DOLPHIN_DIRS.toTypedArray())
            .out.trim().ifEmpty { null }

    /** Azahar's config.ini on shared storage (its user folder is picked in Azahar), or null. */
    fun findAzaharConfig(): String? {
        val roots = listOf(File("/storage/emulated/0")) +
            File("/storage").listFiles().orEmpty().filter { it.name != "self" && it.name != "emulated" }
        val named = Regex("(?i).*(azahar|citra|lime3ds).*")
        for (root in roots) {
            val level1 = root.listFiles().orEmpty().filter { it.isDirectory && it.name != "Android" }
            val candidates = level1.filter { it.name.matches(named) } +
                level1.flatMap { d -> d.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(named) } }
            candidates.map { File(it, "config/config.ini") }
                .firstOrNull { f -> f.isFile && runCatching { f.readText().contains("resolution_factor") }.getOrDefault(false) }
                ?.let { return it.path }
        }
        return null
    }

    /**
     * Azahar was closed: put its own values back where the hook swapped a
     * game's in and they're still there (Azahar may have saved them meanwhile).
     */
    fun restoreAzahar(context: Context) = worker.execute {
        val store = Store.get(context)
        val home = EsDe.home() ?: return@execute
        val marker = File(Hooks.folder(home), "azahar-restore.txt").takeIf { it.isFile } ?: return@execute
        val config = store.azaharConfig?.let(::File)?.takeIf { it.isFile }
        if (config != null) {
            val restored = restoreSwaps(config.readText(), marker.readText())
            if (restored != null) {
                config.writeText(restored)
                store.log("Azahar's own settings are back")
            }
        }
        marker.delete()
    }

    /**
     * [config] with each "key|own value|game's value" line of [marker] undone,
     * where the key still has the game's value; null if nothing changes.
     */
    fun restoreSwaps(config: String, marker: String): String? {
        var text = config
        for (line in marker.lines()) {
            val parts = line.split('|')
            if (parts.size < 3 || parts[0].isBlank()) continue
            val (key, own, game) = parts
            val pattern = Regex("(?m)^" + Regex.escape(key) + " *=(.*)$")
            val now = pattern.find(text)?.groupValues?.get(1)?.trim() ?: continue
            if (now == game) text = pattern.replaceFirst(text, Regex.escapeReplacement("$key = $own"))
        }
        return text.takeIf { it != config }
    }

    /** Eden's installed GPU drivers. */
    fun edenDrivers(): List<String> =
        Shell.sh("ls \"$1\"/*.zip 2>/dev/null", "$EDEN_FILES/gpu_drivers").out.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** What a game's page shows: each setting's own value and the emulator's. Blocking. */
    data class Inspection(val knobs: List<EmuKnob>, val global: Map<String, String?>)

    fun inspect(emulator: Emulator, azaharConfig: String?): Inspection {
        val knobs = when (emulator) {
            Emulator.EDEN -> EmuKnobs.of(emulator).map { if (it.key == EmuKnobs.EDEN_DRIVER.key) EmuKnobs.edenDriver(edenDrivers()) else it }
            else -> EmuKnobs.of(emulator)
        }
        if (emulator.isRetroArch) {
            val base = retroArchSetup()?.let { RetroArch.base(it, emulator, ::read) }.orEmpty()
            return Inspection(knobs, knobs.associate { it.key to (base[it.name] ?: it.default) })
        }
        // The emulator-wide config files, read once each.
        val files = mutableMapOf<String?, String>()
        fun config(name: String?): String = files.getOrPut(name) {
            when (emulator) {
                Emulator.EDEN -> read("$EDEN_FILES/config/config.ini")
                Emulator.DOLPHIN -> dolphinDir()?.let { read("$it/Config/${name ?: "Dolphin.ini"}") }
                Emulator.AZAHAR -> azaharConfig?.let { runCatching { File(it).readText() }.getOrNull() }
                Emulator.ARMSX2 -> armsx2Root()?.let { runCatching { File(it, "PCSX2-Android.ini").readText() }.getOrNull() }
                // DuckStation keeps its own settings in private storage.
                Emulator.DUCKSTATION -> null
                // Handled above.
                Emulator.SWANSTATION, Emulator.MUPEN64, Emulator.FLYCAST -> null
            }.orEmpty()
        }
        val global = knobs.associate { k ->
            val value = Ini.get(config(k.globalFile), k.globalSection ?: k.section, k.globalName ?: k.name)
            // DuckStation's own settings aren't readable, so its value isn't guessed at.
            k.key to if (emulator == Emulator.DUCKSTATION) null else (value?.takeIf { it.isNotEmpty() || k.key == EmuKnobs.EDEN_DRIVER.key } ?: k.default)
        }
        return Inspection(knobs, global)
    }

    // Files in Android/data go through Shizuku's shell.

    private fun read(path: String): String? {
        if (!Shell.ready) return null
        val r = Shell.sh("[ -f \"$1\" ] || exit 3; cat \"$1\"", path)
        return if (r.ok) r.out else null
    }

    /** Writes [text] to [path]; an emptied file (only ever Pocket Automator's lines) goes. */
    private fun write(path: String, text: String): Boolean {
        if (text.isBlank()) return Shell.sh("rm -f \"$1\"", path).ok
        val data = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        return Shell.sh("mkdir -p \"\${1%/*}\" && printf '%s' \"$2\" | base64 -d > \"$1\"", path, data).ok
    }

    /** A copy of [path] as it was before Pocket Automator first changed it. */
    private fun backup(home: File, path: String, emulator: String) {
        val dir = File(Hooks.folder(home), "backups/$emulator").path
        Shell.sh("mkdir -p \"$2\" && { [ -f \"$2/\${1##*/}\" ] || cp \"$1\" \"$2/\"; }", path, dir)
    }

    /** Undo script and README in the "Pocket Automator" folder, for when the app isn't there. */
    private fun writeUndo(home: File, applied: List<Applied>, azahar: String?, retroArchFiles: Map<String, Map<String, String>>) {
        val folder = Hooks.folder(home)
        if (!folder.isDirectory) return
        // The cores' own values, to put back in options files made in RetroArch.
        val setup = if (Shell.ready && applied.any { isRetroArch(it) && it.file !in retroArchFiles }) retroArchSetup() else null
        val bases = mutableMapOf<Emulator, Map<String, String>>()
        val base = { knob: EmuKnob -> setup?.let { s -> bases.getOrPut(knob.emulator) { RetroArch.base(s, knob.emulator, ::read) }[knob.name] } }
        Hooks.writeIfChanged(File(folder, Hooks.UNDO), undoScript(home, applied, azahar, retroArchFiles, base))
        Hooks.writeIfChanged(File(folder, "README.txt"), README)
    }

    fun undoScript(
        home: File,
        applied: List<Applied>,
        azahar: String?,
        retroArchFiles: Map<String, Map<String, String>> = emptyMap(),
        retroArchBase: (EmuKnob) -> String? = { null },
    ): String {
        val folder = Hooks.folder(home)
        val q = Hooks::quote
        val out = StringBuilder()
        out.append(
            """
            |#!/bin/sh
            |# Takes back every emulator setting Pocket Automator changed, and ES-DE's
            |# game hook, without needing the app. Run it from the handheld's settings
            |# ("Run script as Root"), or over adb:  sh ${q(File(folder, Hooks.UNDO).path)}
            |# Pocket Automator rewrites this file whenever its settings change.
            |
            |""".trimMargin(),
        )
        for ((file, options) in retroArchFiles.toSortedMap()) {
            out.append("# A RetroArch options file Pocket Automator made: removed while it's as it wrote it\n")
            out.append("f=${q(file)}; [ \"\$(md5sum \"\$f\" 2>/dev/null | cut -d' ' -f1)\" = ${md5(RetroArch.render(options))} ] && rm -f \"\$f\"\n")
        }
        for (a in applied.sortedBy { it.file }) {
            val knob = EmuKnobs.byKey(a.knob) ?: continue
            if (knob.emulator.isRetroArch && a.file in retroArchFiles) continue
            out.append("# ${a.game}: ${knob.emulator.title} ${knob.title.lowercase()}\n")
            if (knob.emulator.isRetroArch) {
                // An options file made in RetroArch: the core's own value back, where Pocket Automator's is still there.
                val line = "^${knob.name} = \"${a.value}\"\$"
                val action = retroArchBase(knob)?.let { own -> "s|$line|${knob.name} = \"$own\"|" } ?: "\\|$line|d"
                out.append("f=${q(a.file)}; [ -f \"\$f\" ] && sed -i '$action' \"\$f\"\n")
                continue
            }
            val sed = when (knob.emulator) {
                Emulator.EDEN -> "-e 's/^${knob.name}\\\\use_global=false\$/${knob.name}\\\\use_global=true/' " +
                    "-e '/^${knob.name}\\\\default=/d' -e '/^${knob.name}=/d'"
                else -> "-e '/^${knob.name} *=/d'"
            }
            out.append("f=${q(a.file)}; [ -f \"\$f\" ] && sed -i $sed \"\$f\"\n")
        }
        out.append("# Azahar: its own settings back, if a game's are swapped in\n")
        out.append("C=${q(azahar.orEmpty())}; M=${q(File(folder, "azahar-restore.txt").path)}\n")
        val d = '$'
        out.append(
            """
            |if [ -f "${d}M" ] && [ -f "${d}C" ]; then
            |  while IFS='|' read -r k o a; do [ -n "${d}k" ] && sed -i "s/^${d}k *=.*/${d}k = ${d}o/" "${d}C"; done < "${d}M"
            |fi
            |""".trimMargin(),
        )
        out.append("rm -f \"\$M\" ${q(File(folder, "azahar.txt").path)}\n")
        out.append("# ES-DE's game hook\n")
        out.append("rm -f ${Hooks.stubs(home).joinToString(" ") { q(it.path) }} ${q(File(folder, "on-game.sh").path)}\n")
        out.append("echo \"Pocket Automator's game settings are undone.\"\n")
        return out.toString()
    }

    private val README = """
        |Pocket Automator: per-game settings
        |
        |Pocket Automator can give a game its own settings: the handheld's (performance,
        |fan...) and some of its emulator's (resolution, accuracy, fixes). The
        |emulator ones are written where the emulators keep their own per-game settings:
        |
        |  Eden         Android/data/dev.eden.eden_emulator/files/config/custom/<title ID>.ini
        |  Dolphin      Android/data/org.dolphinemu.dolphinemu/files/GameSettings/<game ID>.ini
        |  ARMSX2       gamesettings/<serial>_<CRC>.ini in ARMSX2's folder
        |  DuckStation  Android/data/com.github.stenzek.duckstation/files/gamesettings/<serial>.ini
        |  RetroArch    RetroArch/config/<core>/<game>.opt (SwanStation, Mupen64Plus-Next,
        |               Flycast): the core's options for every game, with the game's on top
        |  Azahar       (no per-game settings) they're swapped in as the game starts
        |               from ES-DE, and put back when you're back in ES-DE or Azahar closes.
        |
        |A setting you gave a game in the emulator itself is never changed.
        |
        |This folder
        |  game.txt              the last game ES-DE started (written by ES-DE's hook)
        |  on-game.sh            the hook ES-DE runs as games start and end
        |  azahar.txt            games' own Azahar resolutions
        |  backups/              emulator files as they were before the first change
        |  $UNDO_NAME  takes everything back
        |
        |To undo it all without the app: run "$UNDO_NAME" from the handheld's settings
        |("Run script as Root"). To stop just the ES-DE hook: delete this folder, or
        |the pocket-automator.sh files in ES-DE/scripts/game-start and game-end.
        |In the app, a game's page has "Reset this game", and the Games page
        |"Undo all emulator changes".
        |""".trimMargin()

    private const val UNDO_NAME = Hooks.UNDO

    private fun md5(text: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun appliedToJson(list: List<Applied>): String = JSONArray().apply {
        list.forEach { a -> put(JSONObject().put("game", a.game).put("knob", a.knob).put("file", a.file).put("value", a.value)) }
    }.toString()

    fun appliedFromJson(text: String?): List<Applied> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Applied(o.optString("game"), o.optString("knob"), o.optString("file"), o.optString("value"))
                .takeIf { it.file.isNotEmpty() && it.knob.isNotEmpty() }
        }
    }.getOrDefault(emptyList())
}
