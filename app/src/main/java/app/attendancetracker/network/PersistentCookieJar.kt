package app.attendancetracker.network

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

interface CookiePersistence {
    fun loadAll(): List<Cookie>
    fun saveAll(cookies: List<Cookie>)
    fun clear()
}

class InMemoryCookiePersistence : CookiePersistence {
    private var stored: List<Cookie> = emptyList()
    override fun loadAll(): List<Cookie> = stored
    override fun saveAll(cookies: List<Cookie>) {
        stored = cookies
    }
    override fun clear() {
        stored = emptyList()
    }
}

/**
 * Persistent OkHttp CookieJar supporting Architecture B handoff from Android WebView CookieManager.
 * Never logs cookie names or values.
 */
class PersistentCookieJar(
    private val persistence: CookiePersistence = InMemoryCookiePersistence(),
) : CookieJar {

    private val lock = Any()
    private val cache = mutableListOf<Cookie>()

    init {
        synchronized(lock) {
            cache.addAll(persistence.loadAll())
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            Log.d("PersistentCookieJar", "saveFromResponse: url=$url, incomingCount=${cookies.size}")
            for (incoming in cookies) {
                // If a cookie has an explicit expiration that is past (<= now), remove it (server expired it).
                // If it is a session cookie (persistent is false), expiresAt is Long.MIN_VALUE or not set.
                // OkHttp sets expiresAt = HttpDate.MAX_DATE for session cookies built without expiresAt,
                // BUT incoming Set-Cookie headers from server responses have persistent=false and expiresAt=Long.MIN_VALUE!
                val isExplicitlyExpired = incoming.persistent && incoming.expiresAt <= now
                cache.removeAll { existing ->
                    existing.name == incoming.name &&
                        existing.domain == incoming.domain &&
                        existing.path == incoming.path
                }
                if (!isExplicitlyExpired) {
                    cache.add(incoming)
                } else {
                    Log.d("PersistentCookieJar", "saveFromResponse: dropped explicitly expired cookie (expiresAt=${incoming.expiresAt}, now=$now)")
                }
            }
            persistence.saveAll(cache.toList())
            Log.d("PersistentCookieJar", "saveFromResponse: newTotalInCache=${cache.size}")
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val removedExpired = cache.removeAll {
                val expired = it.persistent && it.expiresAt <= now
                if (expired) {
                    Log.d("PersistentCookieJar", "loadForRequest: pruned expired cookie (expiresAt=${it.expiresAt}, now=$now)")
                }
                expired
            }
            if (removedExpired) {
                persistence.saveAll(cache.toList())
            }
            val matched = cache.filter { it.matches(url) }
            Log.d("PersistentCookieJar", "loadForRequest: url=$url, totalInCache=${cache.size}, matchedCount=${matched.size}")
            return matched
        }
    }

    /**
     * Imports a raw `CookieManager.getInstance().getCookie(url)` header string
     * into the OkHttp cookie jar after WebView login succeeds.
     */
    fun importFromWebViewCookieHeader(urlString: String, cookieHeader: String?) {
        if (cookieHeader.isNullOrBlank()) {
            Log.w("PersistentCookieJar", "importFromWebViewCookieHeader: cookieHeader is null or blank for $urlString")
            return
        }
        val httpUrl = urlString.toHttpUrlOrNull() ?: return
        val parsed = cookieHeader.split(';')
            .mapNotNull { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isEmpty()) return@mapNotNull null
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(httpUrl.host)
                    .path("/")
                    .apply { if (httpUrl.isHttps) secure() }
                    .build()
            }
        Log.d("PersistentCookieJar", "importFromWebViewCookieHeader: parsed ${parsed.size} cookies for ${httpUrl.host}")
        saveFromResponse(httpUrl, parsed)
    }

    fun hasCookiesFor(urlString: String): Boolean {
        val httpUrl = urlString.toHttpUrlOrNull() ?: return false
        return loadForRequest(httpUrl).isNotEmpty()
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            persistence.clear()
        }
    }
}
