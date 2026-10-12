package com.yzinsta.downloader.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramCookiesTest {

    // Shape of CookieManager.getCookie("https://www.instagram.com") after a web login.
    private val header = "csrftoken=AbC123; mid=Zx-_yQALAAF; ig_did=1A2B-3C; " +
        "ds_user_id=4567890; sessionid=4567890%3AqWeRtY%3A12%3AAYc-abc=; rur=\"CLN\\0544567890\\0541760000000:01f7\""

    @Test
    fun `parses all cookies from CookieManager header`() {
        val cookies = InstagramCookies.fromCookieHeader(header)
        assertEquals("AbC123", cookies.values["csrftoken"])
        assertEquals("4567890", cookies.values["ds_user_id"])
        assertEquals("Zx-_yQALAAF", cookies.values["mid"])
        assertTrue(cookies.isComplete)
    }

    @Test
    fun `keeps url-encoded values and embedded equals signs verbatim`() {
        val cookies = InstagramCookies.fromCookieHeader(header)
        assertEquals("4567890%3AqWeRtY%3A12%3AAYc-abc=", cookies.values["sessionid"])
    }

    @Test
    fun `strips surrounding quotes`() {
        val cookies = InstagramCookies.fromCookieHeader(header)
        assertEquals("CLN\\0544567890\\0541760000000:01f7", cookies.values["rur"])
    }

    @Test
    fun `logged-out header reports missing cookies`() {
        val cookies = InstagramCookies.fromCookieHeader("csrftoken=abc; mid=x")
        assertFalse(cookies.isComplete)
        assertEquals(listOf("sessionid", "ds_user_id"), cookies.missing)
    }

    @Test
    fun `empty and junk input`() {
        assertEquals(emptyMap<String, String>(), InstagramCookies.fromCookieHeader(null).values)
        assertEquals(emptyMap<String, String>(), InstagramCookies.fromCookieHeader("").values)
        assertEquals(emptyMap<String, String>(), InstagramCookies.fromCookieHeader(";; =x; novalue; a=").values)
    }

    @Test
    fun `json round trip`() {
        val cookies = InstagramCookies.fromCookieHeader(header)
        assertEquals(cookies, InstagramCookies.fromJson(cookies.toJson()))
    }

    @Test
    fun `toString never leaks values`() {
        val text = InstagramCookies.fromCookieHeader(header).toString()
        assertFalse(text.contains("qWeRtY"))
        assertFalse(text.contains("AbC123"))
    }

    @Test
    fun `pasted sessionid alone derives user id and csrf token`() {
        val cookies = InstagramCookies.fromUserInput("  4567890%3AqWeRtY%3A12%3AAYc-abc  ", java.util.Random(1))
        assertEquals("4567890%3AqWeRtY%3A12%3AAYc-abc", cookies.values["sessionid"])
        assertEquals("4567890", cookies.values["ds_user_id"])
        assertEquals(32, cookies.values["csrftoken"]!!.length)
        assertTrue(cookies.isComplete)
    }

    @Test
    fun `pasted cookie header keeps real values`() {
        val cookies = InstagramCookies.fromUserInput(header)
        assertEquals(InstagramCookies.fromCookieHeader(header), cookies)
    }

    @Test
    fun `pasted json and decoded colon form`() {
        val cookies = InstagramCookies.fromUserInput("""{"sessionid":"42:tok:1","csrftoken":"c"}""")
        assertEquals("42", cookies.values["ds_user_id"])
        assertEquals("c", cookies.values["csrftoken"])
    }

    @Test
    fun `garbage input stays incomplete`() {
        assertFalse(InstagramCookies.fromUserInput("").isComplete)
        assertFalse(InstagramCookies.fromUserInput("{broken").isComplete)
        assertFalse(InstagramCookies.fromUserInput("mid=x; csrftoken=y").isComplete)
        // sessionid without a numeric prefix: user id can't be derived
        assertEquals(listOf("ds_user_id"), InstagramCookies.fromUserInput("abcdef").missing)
    }
}
