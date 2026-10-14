package com.yzinsta.downloader.data.download

import java.util.Base64

/** Something on Instagram the app can download. */
sealed interface InstaLink {
    /** Post, reel or IGTV video. */
    data class Post(val shortcode: String) : InstaLink

    /** One story item of [username]. */
    data class Story(val username: String, val mediaId: String) : InstaLink

    /** A whole highlight. Share links carry only its id, not the owner. */
    data class Highlight(val id: String) : InstaLink

    /** A profile page; downloaded as a background job with options. */
    data class Profile(val username: String) : InstaLink
}

/** Finds the first Instagram link in a link or in shared text. */
object LinkParser {

    private const val HOST = """(?:https?://)?(?:www\.|m\.)?(?:instagram\.com|instagr\.am)/"""

    // instagram.com/p/<code>, /reel/<code>, /reels/<code>, /tv/<code>, optionally with a
    // username segment in front (instagram.com/<user>/p/<code>) and any query string.
    private val post = Regex(HOST + """(?:[A-Za-z0-9_.]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]{5,})""", RegexOption.IGNORE_CASE)
    private val highlight = Regex(HOST + """stories/highlights/(\d+)""", RegexOption.IGNORE_CASE)
    private val story = Regex(HOST + """stories/([A-Za-z0-9_.]+)/(\d+)""", RegexOption.IGNORE_CASE)

    // "Share > Copy link" on a highlight gives instagram.com/s/<base64("highlight:<id>")>?story_media_id=...
    private val shareLink = Regex(HOST + """s/([A-Za-z0-9+/_=-]+)""", RegexOption.IGNORE_CASE)
    private val decodedHighlight = Regex("""^highlight:(\d+)$""")

    // instagram.com/<username>[/][?...], also a profile tab (/reels/, /tagged/, ...) and the
    // app's instagram.com/_u/<username> form - checked last, after all content links.
    private val profile = Regex(
        HOST + """(?:_u/)?@?([A-Za-z0-9_.]{1,30})/?(?:(?:reels|tagged|saved|igtv|guides|feed|channel)/?)?(?=$|[?#\s])""",
        RegexOption.IGNORE_CASE,
    )

    // Just a username typed into the field: "zeynep" or "@zeynep" (the whole input, one word).
    private val bareUsername = Regex("""^@?([A-Za-z0-9_.]{1,30})$""")

    /** First path segments that are Instagram pages, not usernames. */
    private val reserved = setOf(
        "p", "reel", "reels", "tv", "stories", "explore", "accounts", "direct", "s", "about", "legal",
        "developer", "web", "challenge", "emails", "session", "privacy", "terms", "api", "graphql",
        "static", "oauth", "invites", "nametag", "lite", "directory", "topics", "locations", "tags", "ar",
    )

    fun parse(input: String?): InstaLink? {
        if (input.isNullOrBlank()) return null
        // Turkish keyboards turn "instagram" into "İnstagram" or a typed i into ı; Instagram
        // URLs and usernames are ASCII only, so mapping them back is safe.
        val text = input.replace('ı', 'i').replace('İ', 'I')
        highlight.find(text)?.let { return InstaLink.Highlight(it.groupValues[1]) }
        shareLink.find(text)?.let { match ->
            decodeBase64(match.groupValues[1])?.let { decodedHighlight.find(it) }
                ?.let { return InstaLink.Highlight(it.groupValues[1]) }
        }
        story.find(text)?.let { match ->
            val user = match.groupValues[1]
            if (!user.equals("highlights", ignoreCase = true)) return InstaLink.Story(user, match.groupValues[2])
        }
        post.find(text)?.let { return InstaLink.Post(it.groupValues[1]) }
        profile.find(text)?.let { match ->
            val user = match.groupValues[1]
            if (isUsername(user)) return InstaLink.Profile(user)
        }
        bareUsername.find(text.trim())?.let { match ->
            val user = match.groupValues[1]
            if (isUsername(user) && user.any { it.isLetter() }) return InstaLink.Profile(user)
        }
        return null
    }

    private fun isUsername(user: String) =
        user.lowercase() !in reserved && !user.startsWith('.') && !user.endsWith('.') && ".." !in user

    private fun decodeBase64(value: String): String? {
        val standard = value.replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
