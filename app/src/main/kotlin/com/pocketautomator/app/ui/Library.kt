package com.pocketautomator.app.ui

import android.content.Context
import android.os.Environment
import com.pocketautomator.app.EsDe
import java.io.File

/**
 * Your ES-DE library as the screens use it: recent games, and each
 * emulator's own recent games, with their art. Empty when ES-DE isn't found
 * or Pocket Automator can't read storage yet.
 */
class Library(val home: File?, games: List<EsDe.Game>, installed: Set<String>) {

    class Entry(val game: EsDe.Game, val emulator: String?, val cover: File?, val wide: File?)

    private val entries: List<Entry> = games.map { g ->
        Entry(g, EsDe.emulatorFor(g, installed), home?.let { EsDe.cover(it, g) }, home?.let { EsDe.wide(it, g) })
    }
    private val byGame = entries.associateBy { it.game }

    val size: Int get() = entries.size

    /** Every game (apps and emulators in ES-DE's lists aren't games), by name. */
    val games: List<Entry> by lazy { entries.filter { EsDe.isGame(it.game) }.sortedBy { it.game.name.lowercase() } }

    fun find(id: String): Entry? = entries.firstOrNull { it.game.id == id }
    val recent: List<Entry> = EsDe.recent(games, 14).mapNotNull { byGame[it] }

    /** [pkg]'s games, most recently played first, then the rest with art. */
    fun forEmulator(pkg: String, count: Int): List<Entry> =
        entries.filter { it.emulator == pkg && it.cover != null }
            .sortedWith(compareByDescending<Entry> { it.game.lastPlayed }.thenByDescending { it.game.favorite })
            .take(count)

    /** A wide picture for the banner: the most recent game that has one. */
    val banner: File? get() = recent.firstNotNullOfOrNull { it.wide }

    /** The most recent game played on [pkg], if it's in ES-DE. */
    fun lastOn(pkg: String): Entry? = recent.firstOrNull { it.emulator == pkg }

    companion object {
        val EMPTY = Library(null, emptyList(), emptySet())

        fun canRead(): Boolean = Environment.isExternalStorageManager()

        /** Blocking: reads every gamelist. */
        fun load(context: Context, installed: Set<String>): Library {
            if (!canRead()) return EMPTY
            val home = EsDe.home() ?: return EMPTY
            return Library(home, EsDe.load(home), installed)
        }
    }
}
