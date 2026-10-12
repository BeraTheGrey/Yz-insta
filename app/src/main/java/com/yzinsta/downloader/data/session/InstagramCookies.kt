package com.yzinsta.downloader.data.session

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Random

/** Instagram web cookies as captured from the WebView. Values are kept verbatim (URL-encoded). */
data class InstagramCookies(val values: Map<String, String>) {

    val missing: List<String> get() = REQUIRED.filter { values[it].isNullOrBlank() }

    val isComplete: Boolean get() = missing.isEmpty()

    fun toJson(): String = JSONObject(values).toString()

    override fun toString(): String = "InstagramCookies(names=${values.keys.sorted()})" // never log values

    companion object {
        /** Keep in sync with REQUIRED_COOKIES in igbridge.py. */
        val REQUIRED = listOf("sessionid", "csrftoken", "ds_user_id")

        /** Parses a `Cookie` header as returned by `CookieManager.getCookie(url)`: `"a=1; b=2"`. */
        fun fromCookieHeader(header: String?): InstagramCookies {
            if (header.isNullOrBlank()) return InstagramCookies(emptyMap())
            val map = LinkedHashMap<String, String>()
            for (part in header.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val name = part.substring(0, eq).trim()
                // Values may themselves contain '=' (base64); only split on the first one.
                val value = part.substring(eq + 1).trim().removeSurrounding("\"")
                if (name.isNotEmpty() && value.isNotEmpty()) map[name] = value
            }
            return InstagramCookies(map)
        }

        /**
         * Manual fallback login: accepts a full Cookie header ("a=1; b=2"), a JSON object,
         * or just the sessionid value. ds_user_id is the numeric prefix of sessionid
         * ("<id>%3A<token>%3A..."), so it can be derived. csrftoken is only echoed back by
         * Instaloader as X-CSRFToken; if it is missing a random one is generated.
         */
        fun fromUserInput(input: String, random: Random = SecureRandom()): InstagramCookies {
            val text = input.trim()
            val parsed = when {
                text.startsWith("{") -> runCatching { fromJson(text) }.getOrElse { InstagramCookies(emptyMap()) }
                text.contains('=') -> fromCookieHeader(text)
                text.isNotEmpty() -> InstagramCookies(mapOf("sessionid" to text.removeSurrounding("\"")))
                else -> InstagramCookies(emptyMap())
            }
            val sessionId = parsed.values["sessionid"]?.takeIf { it.isNotBlank() } ?: return parsed
            val values = LinkedHashMap(parsed.values)
            if (values["ds_user_id"].isNullOrBlank()) {
                USER_ID_PREFIX.find(sessionId)?.let { values["ds_user_id"] = it.groupValues[1] }
            }
            if (values["csrftoken"].isNullOrBlank()) {
                values["csrftoken"] = String(CharArray(32) { CSRF_ALPHABET[random.nextInt(CSRF_ALPHABET.length)] })
            }
            return InstagramCookies(values)
        }

        private val USER_ID_PREFIX = Regex("""^(\d+)(?:%3A|:)""", RegexOption.IGNORE_CASE)
        private const val CSRF_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        fun fromJson(json: String): InstagramCookies {
            val obj = JSONObject(json)
            return InstagramCookies(obj.keys().asSequence().associateWith { obj.getString(it) })
        }
    }
}
