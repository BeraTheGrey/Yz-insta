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
}
