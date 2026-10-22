package com.trailguide.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SavedPlace(val name: String, val lat: Double, val lng: Double)

class MapPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mapboxToken: String
        get() = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    fun hasToken(): Boolean = mapboxToken.isNotBlank()
    fun isOnboardingDone(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    fun markOnboardingDone() = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE, value).apply()

    var westernVoice: Boolean
        get() = prefs.getBoolean(KEY_WESTERN, true)
        set(value) = prefs.edit().putBoolean(KEY_WESTERN, value).apply()

    var useMetric: Boolean
        get() = prefs.getBoolean(KEY_METRIC, true)
        set(value) = prefs.edit().putBoolean(KEY_METRIC, value).apply()

    var avoidHighways: Boolean
        get() = prefs.getBoolean(KEY_AVOID_HWY, false)
        set(value) = prefs.edit().putBoolean(KEY_AVOID_HWY, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_ON, value).apply()

    var northUp: Boolean
        get() = prefs.getBoolean(KEY_NORTH_UP, false)
        set(value) = prefs.edit().putBoolean(KEY_NORTH_UP, value).apply()

    var homeLat: Double
        get() = bitsToDouble(prefs.getLong(KEY_HOME_LAT, nanBits()))
        set(value) = prefs.edit().putLong(KEY_HOME_LAT, doubleToBits(value)).apply()

    var homeLng: Double
        get() = bitsToDouble(prefs.getLong(KEY_HOME_LNG, nanBits()))
        set(value) = prefs.edit().putLong(KEY_HOME_LNG, doubleToBits(value)).apply()

    fun hasHome(): Boolean = !homeLat.isNaN() && !homeLng.isNaN()
    fun clearHome() {
        prefs.edit().putLong(KEY_HOME_LAT, nanBits()).putLong(KEY_HOME_LNG, nanBits()).apply()
    }

    fun getRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
    }

    fun addRecentSearch(query: String) {
        if (query.isBlank()) return
        val list = getRecentSearches().toMutableList()
        list.remove(query)
        list.add(0, query)
        while (list.size > 12) list.removeAt(list.lastIndex)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    fun getSavedPlaces(): List<SavedPlace> {
        val raw = prefs.getString(KEY_SAVED, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(SavedPlace(o.getString("name"), o.getDouble("lat"), o.getDouble("lng")))
            }
        }
    }

    fun savePlace(place: SavedPlace) {
        val list = getSavedPlaces().toMutableList()
        list.removeAll { it.name == place.name }
        list.add(0, place)
        while (list.size > 20) list.removeAt(list.lastIndex)
        writeSaved(list)
    }

    fun removeSavedPlace(name: String) {
        writeSaved(getSavedPlaces().filter { it.name != name })
    }

    private fun writeSaved(list: List<SavedPlace>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng))
        }
        prefs.edit().putString(KEY_SAVED, arr.toString()).apply()
    }


    var drivingRegion: String
        get() = prefs.getString(KEY_REGION, "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString(KEY_REGION, value).apply()

    var githubRepo: String
        get() = prefs.getString(KEY_GITHUB, "AstroLabs-UK/TrailGuide")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_GITHUB, value.trim()).apply()

    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE, value).apply()

    var pendingUpdatePath: String
        get() = prefs.getString(KEY_PENDING_APK, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PENDING_APK, value).apply()

    var pendingUpdateTag: String
        get() = prefs.getString(KEY_PENDING_TAG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PENDING_TAG, value).apply()

    fun styleUrl(): String {
        val token = mapboxToken
        return if (token.isNotBlank()) {
            "https://api.mapbox.com/styles/v1/mapbox/cjpn02blt2cw32sqrt9r8e5c3?access_token=$token"
        } else OPENFREEMAP_LIBERTY
    }

    private fun nanBits() = java.lang.Double.doubleToRawLongBits(Double.NaN)
    private fun doubleToBits(v: Double) = java.lang.Double.doubleToRawLongBits(v)
    private fun bitsToDouble(b: Long) = java.lang.Double.longBitsToDouble(b)

    companion object {
        private const val PREFS_NAME = "trail_guide_prefs"
        private const val KEY_TOKEN = "mapbox_token"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_WESTERN = "western_voice"
        private const val KEY_METRIC = "use_metric"
        private const val KEY_AVOID_HWY = "avoid_highways"
        private const val KEY_SCREEN_ON = "keep_screen_on"
        private const val KEY_NORTH_UP = "north_up"
        private const val KEY_HOME_LAT = "home_lat"
        private const val KEY_HOME_LNG = "home_lng"
        private const val KEY_RECENT = "recent_searches"
        private const val KEY_SAVED = "saved_places"
        private const val KEY_REGION = "driving_region"
        private const val KEY_GITHUB = "github_repo"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_PENDING_APK = "pending_apk"
        private const val KEY_PENDING_TAG = "pending_tag"
        const val OPENFREEMAP_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    }
}
