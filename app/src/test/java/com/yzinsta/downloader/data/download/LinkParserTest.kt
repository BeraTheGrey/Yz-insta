package com.yzinsta.downloader.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkParserTest {

    @Test
    fun `post and reel links`() {
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
        for ((link, code) in cases) assertEquals(link, InstaLink.Post(code), LinkParser.parse(link))
    }

    @Test
    fun `link inside shared text`() {
        val shared = "Check this out! https://www.instagram.com/reel/C9xYz_-AbCd/?igsh=abc via Instagram"
        assertEquals(InstaLink.Post("C9xYz_-AbCd"), LinkParser.parse(shared))
    }

    @Test
    fun `story links`() {
        assertEquals(
            InstaLink.Story("zeynep.k_1", "4005791587892109102"),
            LinkParser.parse("https://www.instagram.com/stories/zeynep.k_1/4005791587892109102/?igsh=MTZ1"),
        )
        assertEquals(
            InstaLink.Story("someone", "3456789012345678901"),
            LinkParser.parse("instagram.com/stories/someone/3456789012345678901"),
        )
    }

    @Test
    fun `highlight links`() {
        assertEquals(
            InstaLink.Highlight("17973718328923841"),
            LinkParser.parse("https://www.instagram.com/stories/highlights/17973718328923841/"),
        )
        // Share > Copy link on a highlight: base64("highlight:17973718328923841")
        assertEquals(
            InstaLink.Highlight("17973718328923841"),
            LinkParser.parse(
                "https://www.instagram.com/s/aGlnaGxpZ2h0OjE3OTczNzE4MzI4OTIzODQx?story_media_id=4005791587892109102_76742326711&igsh=abc"
            ),
        )
    }

    @Test
    fun `profile links`() {
        val cases = mapOf(
            "https://www.instagram.com/zeynep.k_1/" to "zeynep.k_1",
            "https://instagram.com/zeynep.k_1?igsh=MTZ1" to "zeynep.k_1",
            "instagram.com/zeynep" to "zeynep",
            "Check out https://www.instagram.com/zeynep/ on Instagram" to "zeynep",
        )
        for ((link, user) in cases) assertEquals(link, InstaLink.Profile(user), LinkParser.parse(link))
    }

    @Test
    fun `unsupported or invalid`() {
        listOf(
            null,
            "",
            "hello",
            "https://www.instagram.com/explore/",
            "https://www.instagram.com/accounts/login/",
            "https://www.instagram.com/direct/inbox/",
            "https://www.instagram.com/stories/someuser/",
            "https://www.instagram.com/s/bm90IGEgaGlnaGxpZ2h0", // base64("not a highlight")
            "https://www.instagram.com/s/%%%",
            "https://example.com/p/C1a2B3c4D5e/",
            "https://www.instagram.com/p/",
        ).forEach { assertNull(it, LinkParser.parse(it)) }
    }
}
