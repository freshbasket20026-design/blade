package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "blade_prefs")

data class StoredSession(
    val accessToken: String = "",
    val refreshToken: String = "",
    val userId: String = "",
    val email: String = ""
) {
    val isLoggedIn: Boolean get() = accessToken.isNotBlank() && userId.isNotBlank()
}

data class WatchAffinitySnapshot(
    val categories: Map<String, Int> = emptyMap(),
    val creators: Map<String, Int> = emptyMap(),
    val hashtags: Map<String, Int> = emptyMap(),
    val videos: Map<String, Int> = emptyMap()
)

class AppPreferences(private val context: Context) {

    private object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val LANGUAGE = stringPreferencesKey("language_code")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val USER_ID = stringPreferencesKey("user_id")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val WATCH_AFFINITY = stringPreferencesKey("watch_affinity_json")
    }

    val isDarkMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DARK_MODE] ?: true
    }

    val languageCode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LANGUAGE] ?: "en"
    }

    val sessionFlow: Flow<StoredSession> = context.dataStore.data.map { prefs ->
        StoredSession(
            accessToken = prefs[Keys.ACCESS_TOKEN] ?: "",
            refreshToken = prefs[Keys.REFRESH_TOKEN] ?: "",
            userId = prefs[Keys.USER_ID] ?: "",
            email = prefs[Keys.USER_EMAIL] ?: ""
        )
    }

    suspend fun getSessionSnapshot(): StoredSession = sessionFlow.first()

    suspend fun setDarkMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
    }

    suspend fun setLanguageCode(code: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = code }
    }

    suspend fun saveSession(
        accessToken: String,
        refreshToken: String,
        userId: String,
        email: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
            prefs[Keys.USER_ID] = userId
            prefs[Keys.USER_EMAIL] = email
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USER_EMAIL)
        }
    }

    suspend fun getWatchAffinity(): WatchAffinitySnapshot {
        val raw = context.dataStore.data.first()[Keys.WATCH_AFFINITY].orEmpty()
        if (raw.isBlank()) return WatchAffinitySnapshot()
        return runCatching {
            val root = JSONObject(raw)
            WatchAffinitySnapshot(
                categories = jsonToIntMap(root.optJSONObject("categories")),
                creators = jsonToIntMap(root.optJSONObject("creators")),
                hashtags = jsonToIntMap(root.optJSONObject("hashtags")),
                videos = jsonToIntMap(root.optJSONObject("videos"))
            )
        }.getOrDefault(WatchAffinitySnapshot())
    }

    suspend fun recordWatchAffinity(
        videoId: String,
        creatorId: String,
        category: String,
        hashtags: List<String>,
        weight: Int
    ) {
        if (weight <= 0) return
        context.dataStore.edit { prefs ->
            val root = runCatching {
                val existing = prefs[Keys.WATCH_AFFINITY].orEmpty()
                if (existing.isBlank()) JSONObject() else JSONObject(existing)
            }.getOrDefault(JSONObject())

            val categoriesObj = root.optJSONObject("categories") ?: JSONObject()
            val creatorsObj = root.optJSONObject("creators") ?: JSONObject()
            val hashtagsObj = root.optJSONObject("hashtags") ?: JSONObject()
            val videosObj = root.optJSONObject("videos") ?: JSONObject()

            val cleanCategory = category.trim().lowercase()
            if (cleanCategory.isNotBlank() && cleanCategory != "all") {
                incrementCapped(categoriesObj, cleanCategory, weight)
            }
            if (creatorId.isNotBlank()) {
                incrementCapped(creatorsObj, creatorId, weight)
            }
            hashtags.forEach { tag ->
                val cleanTag = tag.trim().removePrefix("#").lowercase()
                if (cleanTag.isNotBlank()) {
                    incrementCapped(hashtagsObj, cleanTag, weight)
                }
            }
            if (videoId.isNotBlank()) {
                incrementCapped(videosObj, videoId, weight)
            }

            root.put("categories", categoriesObj)
            root.put("creators", creatorsObj)
            root.put("hashtags", hashtagsObj)
            root.put("videos", videosObj)
            prefs[Keys.WATCH_AFFINITY] = root.toString()
        }
    }

    private fun incrementCapped(obj: JSONObject, key: String, delta: Int, maxKeys: Int = 60) {
        val current = obj.optInt(key, 0)
        obj.put(key, (current + delta).coerceAtMost(10_000))
        if (obj.length() > maxKeys) {
            var minKey: String? = null
            var minVal = Int.MAX_VALUE
            val iter = obj.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                val v = obj.optInt(k, 0)
                if (v < minVal && k != key) {
                    minVal = v
                    minKey = k
                }
            }
            if (minKey != null) obj.remove(minKey)
        }
    }

    private fun jsonToIntMap(obj: JSONObject?): Map<String, Int> {
        if (obj == null) return emptyMap()
        val result = mutableMapOf<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            result[k] = obj.optInt(k, 0)
        }
        return result
    }
}
