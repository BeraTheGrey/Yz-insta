package com.yzinsta.downloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcodeParserTest {

    @Test
    fun `supported links`() {
        val cases = mapOf(
            "https://www.instagram.com/p/C1a2B3c4D5e/" to "C1a2B3c4D5e",
            "https://www.instagram.com/reel/C9xYz_-AbCd/?igsh=MWQ1ZGUxMzBkMA==" to "C9xYz_-AbCd",
            "https://instagram.com/reels/DAbCdEfGhIj" to "DAbCdEfGhIj",
            "https://www.instagram.com/tv/B8aBcDeFgHi/?utm_source=ig_web_copy_link" to "B8aBcDeFgHi",
            "http://m.instagram.com/p/C1a2B3c4D5e" to "C1a2B3c4D5e",
            "instagram.com/p/C1a2B3c4D5e" to "C1a2B3c4D5e",
            "https://www.instagram.com/some.user_1/p/C1a2B3c4D5e/" to "C1a2B3c4D5e",
            "https://www.instagram.com/some.user_1/reel/C9xYz_-AbCd/" to "C9xYz_-AbCd",
            "https://instagr.am/p/C1a2B3c4D5e/" to "C1a2B3c4D5e",
            "HTTPS://WWW.INSTAGRAM.COM/P/C1a2B3c4D5e/" to "C1a2B3c4D5e",
        )
        for ((link, code) in cases) assertEquals(link, code, ShortcodeParser.parse(link))
    }

    @Test
    fun `link inside shared text`() {
        val shared = "Check this out! https://www.instagram.com/reel/C9xYz_-AbCd/?igsh=abc via Instagram"
        assertEquals("C9xYz_-AbCd", ShortcodeParser.parse(shared))
    }

    @Test
    fun `unsupported or invalid`() {
        listOf(
            null,
            "",
            "hello",
            "https://www.instagram.com/someuser/",
            "https://www.instagram.com/stories/someuser/3456789012345678901/",
            "https://example.com/p/C1a2B3c4D5e/",
            "https://www.instagram.com/p/",
        ).forEach { assertNull(it, ShortcodeParser.parse(it)) }
    }
}
