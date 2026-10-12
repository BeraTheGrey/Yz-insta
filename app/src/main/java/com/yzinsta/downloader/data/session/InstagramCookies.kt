package com.yzinsta.downloader.data.session

import org.json.JSONObject

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

        fun fromJson(json: String): InstagramCookies {
            val obj = JSONObject(json)
            return InstagramCookies(obj.keys().asSequence().associateWith { obj.getString(it) })
        }
    }
}
