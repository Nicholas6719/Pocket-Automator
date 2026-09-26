package com.pocketautomator.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything Pocket Automator keeps: the profiles, the snapshot of values a
 * game changed (see [Plan]), and a few switches. One per process; the screens
 * and the service both watch its flows.
 */
class Store private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("automator", Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _log = MutableStateFlow(loadLog())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** What is going on right now, for the status card. Not stored. */
    data class Now(
        val app: String? = null,
        val profileId: Int? = null,
        /** The game ES-DE started in [app], when known. */
        val game: GameEvent? = null,
        val readings: Commands.Readings? = null,
        val watching: Boolean = false,
    )

    private val _now = MutableStateFlow(Now())
    val now: StateFlow<Now> = _now.asStateFlow()

    fun setNow(transform: (Now) -> Now) {
        _now.value = transform(_now.value)
    }

    private fun load(): List<Profile> =
        ProfilesJson.fromJson(prefs.getString(PROFILES, null))
            ?: listOf(Profile(Profile.DEFAULT_ID, "Default"))

    fun profile(id: Int): Profile? = _profiles.value.firstOrNull { it.id == id }

    fun default(): Profile = _profiles.value.first { it.isDefault }

    /** Replaces every profile, as a restore does. */
    fun replaceAll(profiles: List<Profile>) {
        val clean = ProfilesJson.fromJson(ProfilesJson.toJson(profiles).toString()) ?: return
        prefs.edit {
            putString(PROFILES, ProfilesJson.toJson(clean).toString())
            putInt(NEXT, (clean.maxOf { it.id } + 1).coerceAtLeast(prefs.getInt(NEXT, 1)))
        }
        _profiles.value = clean
    }

    fun save(profile: Profile) {
        val list = _profiles.value
        replaceAll(if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile)
    }

    fun create(name: String, settings: Map<Knob, Int> = emptyMap(), apps: Set<String> = emptySet()): Profile {
        val list = _profiles.value
        val id = maxOf(prefs.getInt(NEXT, 1), list.maxOf { it.id } + 1)
        prefs.edit { putInt(NEXT, id + 1) }
        val profile = Profile(id, Plan.uniqueName(list, name), settings)
        replaceAll(Plan.relinked(list + profile, id, apps))
        return profile
    }

    fun rename(id: Int, name: String) {
        val p = profile(id) ?: return
        save(p.copy(name = Plan.uniqueName(_profiles.value, name, except = id)))
    }

    fun setKnob(id: Int, knob: Knob, value: Int?) {
        val p = profile(id) ?: return
        save(p.copy(settings = if (value == null) p.settings - knob else p.settings + (knob to value)))
    }

    fun setAutoClose(id: Int, on: Boolean) {
        val p = profile(id) ?: return
        save(p.copy(autoClose = on))
    }

    fun setApps(id: Int, apps: Set<String>) {
        replaceAll(Plan.relinked(_profiles.value, id, apps))
    }

    fun delete(id: Int) {
        if (id == Profile.DEFAULT_ID) return
        replaceAll(_profiles.value.filter { it.id != id })
    }

    /** Whether the starter profiles have been offered, so they are made once. */
    var seeded: Boolean
        get() = prefs.getBoolean(SEEDED, false)
        set(value) = prefs.edit { putBoolean(SEEDED, value) }

    var snapshot: Map<Knob, Int>
        get() = ProfilesJson.settingsFromJson(prefs.getString(SNAPSHOT, null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        set(value) = prefs.edit { putString(SNAPSHOT, ProfilesJson.settingsToJson(value).toString()) }

    fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(ENABLED, on) }
        _enabled.value = on
    }

    private val _autoClose = MutableStateFlow(prefs.getBoolean(AUTO_CLOSE, true))

    /** Whether emulators left behind are closed (see [AutoClose]). */
    val autoClose: StateFlow<Boolean> = _autoClose.asStateFlow()

    fun setAutoClose(on: Boolean) {
        prefs.edit { putBoolean(AUTO_CLOSE, on) }
        _autoClose.value = on
    }

    private val _autoCloseDelay = MutableStateFlow(prefs.getInt(AUTO_CLOSE_DELAY, 10))

    /** Seconds between the screen going off (or another emulator opening) and the close. */
    val autoCloseDelay: StateFlow<Int> = _autoCloseDelay.asStateFlow()

    fun setAutoCloseDelay(seconds: Int) {
        val clean = seconds.coerceIn(5, 120)
        prefs.edit { putInt(AUTO_CLOSE_DELAY, clean) }
        _autoCloseDelay.value = clean
    }

    private val _closeOnHome = MutableStateFlow(prefs.getBoolean(CLOSE_ON_HOME, true))

    /** Whether games left on the home screen (ES-DE) close after the delay too, screen on or not. */
    val closeOnHome: StateFlow<Boolean> = _closeOnHome.asStateFlow()

    fun setCloseOnHome(on: Boolean) {
        prefs.edit { putBoolean(CLOSE_ON_HOME, on) }
        _closeOnHome.value = on
    }

    private val _autoCloseApps = MutableStateFlow(prefs.getStringSet(AUTO_CLOSE_APPS, null)?.toSet() ?: emptySet())

    /** Apps without a profile that auto-close too (a browser, say). */
    val autoCloseApps: StateFlow<Set<String>> = _autoCloseApps.asStateFlow()

    fun setAutoCloseApps(apps: Set<String>) {
        prefs.edit { putStringSet(AUTO_CLOSE_APPS, apps) }
        _autoCloseApps.value = apps
    }

    private val _keepAliveOn = MutableStateFlow(prefs.getBoolean(KEEP_ALIVE_ON, true))

    /**
     * Whether the root watchdog keeps Shizuku, Pocket Automator and [keepAlive]
     * running, and starts Shizuku after a restart (see [Background]).
     */
    val keepAliveOn: StateFlow<Boolean> = _keepAliveOn.asStateFlow()

    fun setKeepAliveOn(on: Boolean) {
        prefs.edit { putBoolean(KEEP_ALIVE_ON, on) }
        _keepAliveOn.value = on
    }

    private val _keepAlive = MutableStateFlow(prefs.getStringSet(KEEP_ALIVE, null)?.toSet() ?: emptySet())

    /** Apps kept running 24/7 (Syncthing Fork, say). */
    val keepAlive: StateFlow<Set<String>> = _keepAlive.asStateFlow()

    fun setKeepAlive(apps: Set<String>) {
        prefs.edit { putStringSet(KEEP_ALIVE, apps) }
        _keepAlive.value = apps
    }

    /** Whether the keep-alive list has been given its starting apps, so it happens once. */
    var keepAliveSeeded: Boolean
        get() = prefs.getBoolean(KEEP_ALIVE_SEEDED, false)
        set(value) = prefs.edit { putBoolean(KEEP_ALIVE_SEEDED, value) }

    private val _games = MutableStateFlow(
        GamesJson.fromJson(prefs.getString(GAMES, null)?.let { runCatching { JSONArray(it) }.getOrNull() }),
    )

    /** Games with settings of their own (see [GameProfile]). */
    val games: StateFlow<List<GameProfile>> = _games.asStateFlow()

    fun game(id: String): GameProfile? = _games.value.firstOrNull { it.id == id }

    /** Replaces every game's settings, as a restore does. */
    fun replaceGames(games: List<GameProfile>) {
        val clean = games.filter { !it.isEmpty }.distinctBy { it.id }
        prefs.edit { putString(GAMES, GamesJson.toJson(clean).toString()) }
        _games.value = clean
    }

    /** Changes [base]'s settings; a game left with none of its own is dropped. */
    fun editGame(base: GameProfile, change: (GameProfile) -> GameProfile) {
        val updated = change(game(base.id) ?: base)
        replaceGames(_games.value.filter { it.id != base.id } + updated)
    }

    fun deleteGame(id: String) = replaceGames(_games.value.filter { it.id != id })

    private val _gameDetection = MutableStateFlow(prefs.getBoolean(GAME_DETECTION, true))

    /** Whether ES-DE's game hook is installed, so games get their own settings. */
    val gameDetection: StateFlow<Boolean> = _gameDetection.asStateFlow()

    fun setGameDetection(on: Boolean) {
        prefs.edit { putBoolean(GAME_DETECTION, on) }
        _gameDetection.value = on
    }

    private val _suggestAuto = MutableStateFlow(prefs.getBoolean(SUGGEST_AUTO, true))

    /** Whether community suggestions are used for settings you haven't chosen yourself. */
    val suggestAuto: StateFlow<Boolean> = _suggestAuto.asStateFlow()

    fun setSuggestAuto(on: Boolean) {
        prefs.edit { putBoolean(SUGGEST_AUTO, on) }
        _suggestAuto.value = on
    }

    /** Eden's title IDs for games (game id → title ID), found or learned. */
    val edenIds: Map<String, String>
        get() = runCatching {
            val o = JSONObject(prefs.getString(EDEN_IDS, null) ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())

    @Synchronized
    fun setEdenId(game: String, id: String) {
        val o = JSONObject(edenIds + (game to id))
        prefs.edit { putString(EDEN_IDS, o.toString()) }
    }

    /** What Pocket Automator wrote into emulators' files (see [GameSettings]). */
    var applied: List<GameSettings.Applied>
        get() = GameSettings.appliedFromJson(prefs.getString(APPLIED, null))
        set(value) = prefs.edit { putString(APPLIED, GameSettings.appliedToJson(value)) }

    /** Azahar's config.ini on shared storage, once found. */
    var azaharConfig: String?
        get() = prefs.getString(AZAHAR_CONFIG, null)
        set(value) = prefs.edit { putString(AZAHAR_CONFIG, value) }

    private val _gameStatus = MutableStateFlow(emptyMap<String, String>())

    /** Why a game's emulator setting isn't in effect ("game id|knob key" → text). Not stored. */
    val gameStatus: StateFlow<Map<String, String>> = _gameStatus.asStateFlow()

    fun setGameStatus(status: Map<String, String>) {
        _gameStatus.value = status
    }

    /** Whether each switch shows a short message. */
    var messages: Boolean
        get() = prefs.getBoolean(MESSAGES, true)
        set(value) = prefs.edit { putBoolean(MESSAGES, value) }

    /** Adds a timestamped line to the log the Diagnostics page shows (the newest [LOG_SIZE] are kept). */
    fun log(line: String) {
        android.util.Log.i("PocketAutomator", line)
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = (listOf("$stamp  $line") + _log.value).take(LOG_SIZE)
        prefs.edit { putString(LOG, JSONArray(lines).toString()) }
        _log.value = lines
    }

    private fun loadLog(): List<String> = runCatching {
        val a = JSONArray(prefs.getString(LOG, "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    companion object {
        private const val PROFILES = "profiles"
        private const val NEXT = "next"
        private const val SNAPSHOT = "snapshot"
        private const val ENABLED = "enabled"
        private const val MESSAGES = "messages"
        private const val SEEDED = "seeded"
        private const val LOG = "log"
        private const val AUTO_CLOSE = "autoClose"
        private const val AUTO_CLOSE_DELAY = "autoCloseDelay"
        private const val CLOSE_ON_HOME = "closeOnHome"
        private const val AUTO_CLOSE_APPS = "autoCloseApps"
        private const val KEEP_ALIVE_ON = "keepAliveOn"
        private const val KEEP_ALIVE = "keepAlive"
        private const val KEEP_ALIVE_SEEDED = "keepAliveSeeded"
        private const val GAMES = "games"
        private const val GAME_DETECTION = "gameDetection"
        private const val SUGGEST_AUTO = "suggestAuto"
        private const val EDEN_IDS = "edenIds"
        private const val APPLIED = "applied"
        private const val AZAHAR_CONFIG = "azaharConfig"
        private const val LOG_SIZE = 60

        @Volatile private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance = it } }
    }
}
