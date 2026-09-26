package com.pocketautomator.app

import org.json.JSONArray
import org.json.JSONObject

/** Profiles as JSON: how they are stored, and what a backup file holds. */
object ProfilesJson {

    const val FORMAT = "pocket-automator-profiles"
    const val VERSION = 1

    fun settingsToJson(settings: Map<Knob, Int>): JSONObject =
        JSONObject().apply { settings.forEach { (knob, value) -> put(knob.key, value) } }

    fun settingsFromJson(json: JSONObject?): Map<Knob, Int> {
        if (json == null) return emptyMap()
        val out = linkedMapOf<Knob, Int>()
        for (key in json.keys()) {
            val knob = Knob.byKey(key) ?: continue
            if (json.opt(key) is Number) out[knob] = json.getInt(key)
        }
        return out
    }

    fun toJson(profiles: List<Profile>): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("profiles", JSONArray().apply {
            profiles.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("settings", settingsToJson(p.settings))
                    put("apps", JSONArray(p.apps.sorted()))
                    put("autoClose", p.autoClose)
                })
            }
        })
    }

    /**
     * Profiles from [text], or null if it isn't a profiles file. There is
     * always exactly one Default profile, first; ids are unique.
     */
    fun fromJson(text: String?): List<Profile>? {
        if (text.isNullOrBlank()) return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optString("format") != FORMAT) return null
        val array = json.optJSONArray("profiles") ?: return null
        val seen = mutableSetOf<Int>()
        val profiles = mutableListOf<Profile>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            if (id < 0 || !seen.add(id)) continue
            val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                .orEmpty().filter { it.isNotBlank() }.toSet()
            profiles += Profile(
                id = id,
                name = o.optString("name").trim().take(Profile.MAX_NAME).ifBlank { "Profile ${id + 1}" },
                settings = settingsFromJson(o.optJSONObject("settings")),
                apps = if (id == Profile.DEFAULT_ID) emptySet() else apps,
                autoClose = o.optBoolean("autoClose", true),
            )
        }
        val default = profiles.firstOrNull { it.isDefault } ?: Profile(Profile.DEFAULT_ID, "Default")
        // An app belongs to one profile: the first to claim it keeps it.
        val claimed = mutableSetOf<String>()
        val others = profiles.filter { !it.isDefault }.map { p ->
            p.copy(apps = p.apps.filter { claimed.add(it) }.toSet())
        }
        return listOf(default) + others
    }
}
