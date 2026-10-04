package com.keyzai.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("keyzai_session")

/**
 * Penyimpanan sesi: session JWT + profil user + model pilihan.
 * Mirror dari src/lib/auth.js (localStorage) di web.
 */
class SessionStore(private val context: Context) {

    companion object {
        private val TOKEN = stringPreferencesKey("token")
        private val USER_JSON = stringPreferencesKey("user")
        private val MODEL = stringPreferencesKey("model")
        private val EXPIRES_AT = longPreferencesKey("expires_at")
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** Cache sinkron untuk tokenProvider OkHttp (dipanggil dari thread IO). */
    @Volatile
    var cachedToken: String? = null
        private set

    @Volatile
    var cachedUser: User? = null
        private set

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[TOKEN] }
    val userFlow: Flow<User?> = context.dataStore.data.map { raw ->
        raw[USER_JSON]?.let { runCatching { json.decodeFromString<User>(it) }.getOrNull() }
    }
    val modelFlow: Flow<String> = context.dataStore.data.map { it[MODEL] ?: DEFAULT_MODEL }

    /** Muat cache saat aplikasi start (panggil sekali dari Application). */
    suspend fun loadCache() {
        val prefs = context.dataStore.data.first()
        cachedToken = prefs[TOKEN]
        cachedUser = prefs[USER_JSON]?.let { runCatching { json.decodeFromString<User>(it) }.getOrNull() }
    }

    suspend fun saveSession(token: String, user: User, expiresAt: Long?) {
        context.dataStore.edit { p ->
            p[TOKEN] = token
            p[USER_JSON] = json.encodeToString(User.serializer(), user)
            if (expiresAt != null) p[EXPIRES_AT] = expiresAt
        }
        cachedToken = token
        cachedUser = user
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
        cachedToken = null
        cachedUser = null
    }

    suspend fun setModel(modelId: String) {
        context.dataStore.edit { it[MODEL] = modelId }
    }

    fun isLoggedIn(): Boolean = cachedToken != null
}
