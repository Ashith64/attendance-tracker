package app.attendancetracker.data

/**
 * Strips sensitive query parameters (auth tokens, signed URLs, USN, passwords)
 * before persisting source URLs in the local database.
 */
object UrlSanitizer {
    private val sensitiveKeys = setOf(
        "ksign",
        "token",
        "passwd",
        "password",
        "enteredid",
        "usn",
        "username",
        "cookie",
        "session",
    )

    fun sanitize(rawUrl: String): String {
        val qIdx = rawUrl.indexOf('?')
        if (qIdx < 0) return rawUrl
        val base = rawUrl.substring(0, qIdx)
        val query = rawUrl.substring(qIdx + 1)
        if (query.isBlank()) return base

        val filtered = query.split('&')
            .filter { pair ->
                val key = pair.substringBefore('=').lowercase()
                key.isNotBlank() && key !in sensitiveKeys
            }
        return if (filtered.isEmpty()) base else "$base?${filtered.joinToString("&")}"
    }
}
