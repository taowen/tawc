package me.phie.tawc.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Code hosts that publish a user's SSH keys at `https://<host>/<user>.keys`.
 *  Fetched directly, never through the relay. */
enum class KeyHost(val key: String, val label: String, val domain: String) {
    GITHUB("github", "GitHub", "github.com"),
    GITLAB("gitlab", "GitLab", "gitlab.com"),
    CODEBERG("codeberg", "Codeberg", "codeberg.org");

    /** authorized_keys text for [user]; IOException with a message to
     *  show on any failure. Blocking. */
    fun fetch(user: String): String {
        if (!USER.matches(user)) throw IOException("\"$user\" is not a $label username")
        val conn = URL("https://$domain/$user.keys").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            when (val code = conn.responseCode) {
                200 -> Unit
                404 -> throw IOException("no $label user \"$user\"")
                else -> throw IOException("$domain answered HTTP $code")
            }
            val text = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < MAX_BYTES) {
                    val n = input.read(buf, 0, minOf(buf.size, MAX_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
            if (text.isBlank()) throw IOException("$user has no SSH keys on $label")
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val USER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private const val MAX_BYTES = 256 * 1024

        fun fromKey(key: String): KeyHost? = entries.firstOrNull { it.key == key }
    }
}
