package com.yzinsta.downloader.data.session

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AndroidWebCookieJar : WebCookieJar {

    override suspend fun instagramCookies(): InstagramCookies = withContext(Dispatchers.Main) {
        InstagramCookies.fromCookieHeader(CookieManager.getInstance().getCookie(INSTAGRAM_URL))
    }

    override suspend fun clear() = withContext(Dispatchers.Main) {
        val manager = CookieManager.getInstance()
        suspendCancellableCoroutine { cont ->
            manager.removeAllCookies { cont.resume(Unit) }
        }
        manager.flush()
        WebStorage.getInstance().deleteAllData()
    }

    companion object {
        const val INSTAGRAM_URL = "https://www.instagram.com"
    }
}
