package com.pocketautomator.app

import android.content.Context
import org.json.JSONObject

/**
 * Community settings for games on this handheld's chip, bundled with the
 * app (assets/suggestions.json): how well each game runs, and settings
 * people use for it, each with its reason and where it comes from.
 *
 * A suggestion marked [Rec.auto] is used unless you've chosen something for
 * that setting yourself ("Auto, but never over mine"); the rest (lower
 * resolutions, say) are only offered on the game's page.
 */
data class Suggestion(
    val system: String,
    /** The game's name as ES-DE shows it; games are matched by system and name (see [Suggestions.key]). */
    val name: String,
    /** The game's ID where the emulator names files by it (a Switch title ID, say). */
    val gameId: String?,
    val status: Status,
    val note: String,
    val sources: List<String>,
    val settings: List<Rec>,
) {
    val key: String get() = Suggestions.key(system, name)

    enum class Status(val key: String, val label: String) {
        FULL("full", "Full speed"),
        PLAYABLE("playable", "Playable"),
        STRUGGLES("struggles", "Struggles"),
        UNPLAYABLE("unplayable", "Not playable"),
        UNKNOWN("unknown", "No reports");

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: UNKNOWN
        }
    }

    data class Rec(val key: String, val value: String, val why: String, val source: String, val auto: Boolean)
}

object Suggestions {

    /** A game's own "same as the emulator", which also turns a suggestion down. */
    const val GLOBAL = "@global"

    /** What the suggestions were gathered for. */
    var hardware: String = ""
        private set

    @Volatile private var cache: List<Suggestion>? = null

    fun all(context: Context): List<Suggestion> = cache ?: synchronized(this) {
        cache ?: runCatching {
            parse(context.assets.open("suggestions.json").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList()).also { cache = it }
    }

    /** The report for a game, by its system and name. */
    fun find(context: Context, system: String, name: String): Suggestion? = byKey(context)[key(system, name)]

    @Volatile private var index: Map<String, Suggestion>? = null

    fun byKey(context: Context): Map<String, Suggestion> = index ?: all(context).associateBy { it.key }.also { index = it }

    /**
     * How games are matched: system plus the name with accents, case,
     * spacing and punctuation dropped, so the same game matches however its
     * ROM file is named ("Pokémon Omega Ruby" = "pokemonomegaruby").
     */
    fun key(system: String, name: String): String =
        system + "/" + java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]"), "")

    fun parse(text: String): List<Suggestion> {
        val root = JSONObject(text)
        hardware = root.optString("hardware")
        val games = root.optJSONArray("games") ?: return emptyList()
        return (0 until games.length()).mapNotNull { i ->
            val o = games.optJSONObject(i) ?: return@mapNotNull null
            val settings = o.optJSONArray("settings")?.let { a ->
                (0 until a.length()).mapNotNull { j ->
                    val r = a.optJSONObject(j) ?: return@mapNotNull null
                    val key = r.optString("key")
                    if (EmuKnobs.byKey(key) == null) return@mapNotNull null
                    Suggestion.Rec(key, r.optString("value"), r.optString("why"), r.optString("source"), r.optBoolean("auto", false))
                }
            }.orEmpty()
            val sources = o.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Suggestion(
                system = o.optString("system"),
                name = o.optString("name"),
                gameId = o.optString("id").ifBlank { null },
                status = Suggestion.Status.of(o.optString("status")),
                note = o.optString("note"),
                sources = sources.filter { it.isNotBlank() },
                settings = settings,
            ).takeIf { it.name.isNotBlank() && it.system.isNotBlank() }
        }
    }

    /**
     * The emulator settings a game ends up with: the suggestions used
     * automatically, then yours on top ([GLOBAL] meaning the emulator's own).
     */
    fun effective(mine: Map<String, String>?, suggestion: Suggestion?, auto: Boolean): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (auto && suggestion != null) suggestion.settings.filter { it.auto }.forEach { out[it.key] = it.value }
        mine?.forEach { (key, value) -> if (value == GLOBAL) out.remove(key) else out[key] = value }
        return out
    }
}
