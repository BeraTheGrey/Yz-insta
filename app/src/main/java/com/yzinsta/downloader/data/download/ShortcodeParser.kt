package com.yzinsta.downloader.data.download

/** Extracts a post/reel shortcode from a link or from shared text that contains one. */
object ShortcodeParser {

    // instagram.com/p/<code>, /reel/<code>, /reels/<code>, /tv/<code>, optionally with a
    // username segment in front (instagram.com/<user>/p/<code>) and any query string.
    private val pattern = Regex(
        """(?:https?://)?(?:www\.|m\.)?(?:instagram\.com|instagr\.am)/(?:[A-Za-z0-9_.]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]{5,})""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String?): String? = text?.let { pattern.find(it)?.groupValues?.get(1) }
}
