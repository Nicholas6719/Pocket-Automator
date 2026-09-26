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
        private const val AUTO_CLOSE_APPS = "autoCloseApps"
        private const val KEEP_ALIVE_ON = "keepAliveOn"
        private const val KEEP_ALIVE = "keepAlive"
        private const val KEEP_ALIVE_SEEDED = "keepAliveSeeded"
        private const val LOG_SIZE = 60

        @Volatile private var instance: Store? = null

        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance = it } }
    }
}
