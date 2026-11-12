package app.attendancetracker.network

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie

private val Context.cookieDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "portal_cookies",
)

@Serializable
private data class SerializableCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
)

/**
 * Persists OkHttp cookies in app-private DataStore across launches.
 * Does NOT require user authentication so WorkManager background sync can reuse cookies.
 * Never logs cookie names or values.
 */
class DataStoreCookiePersistence(
    private val context: Context,
) : CookiePersistence {

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val KEY_COOKIES_JSON = stringPreferencesKey("cookies_json")
    }

    override fun loadAll(): List<Cookie> = runBlocking(Dispatchers.IO) {
        val prefs = context.cookieDataStore.data.first()
        val raw = prefs[KEY_COOKIES_JSON] ?: return@runBlocking emptyList()
        runCatching {
            json.decodeFromString<List<SerializableCookie>>(raw).mapNotNull { it.toOkHttpCookie() }
        }.getOrElse { emptyList() }
    }

    override fun saveAll(cookies: List<Cookie>) {
        val serializable = cookies.map { c ->
            SerializableCookie(
                name = c.name,
                value = c.value,
                expiresAt = c.expiresAt,
                domain = c.domain,
                path = c.path,
                secure = c.secure,
                httpOnly = c.httpOnly,
                hostOnly = c.hostOnly,
            )
        }
        val encoded = json.encodeToString(serializable)
        runBlocking(Dispatchers.IO) {
            context.cookieDataStore.edit { prefs ->
                prefs[KEY_COOKIES_JSON] = encoded
            }
        }
    }

    override fun clear() {
        runBlocking(Dispatchers.IO) {
            context.cookieDataStore.edit { prefs ->
                prefs.remove(KEY_COOKIES_JSON)
            }
        }
    }

    private fun SerializableCookie.toOkHttpCookie(): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .path(path)
            .apply {
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                if (secure) secure()
                if (httpOnly) httpOnly()
            }
            .build()
    }.getOrNull()
}
