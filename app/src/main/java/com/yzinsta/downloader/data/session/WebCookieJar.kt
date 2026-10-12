package com.yzinsta.downloader.data.session

/** The WebView's own cookie/storage state for instagram.com. */
interface WebCookieJar {
    /** Cookies the WebView currently holds for instagram.com. */
    suspend fun instagramCookies(): InstagramCookies

    /** Removes every WebView cookie and all web storage (logout). */
    suspend fun clear()
}
