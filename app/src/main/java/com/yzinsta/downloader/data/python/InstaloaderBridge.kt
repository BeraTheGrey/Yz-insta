package com.yzinsta.downloader.data.python

import com.yzinsta.downloader.data.download.DownloadedPost
import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.domain.Outcome
import java.io.File

/**
 * Everything the app asks Instaloader to do. Implementations must be main-safe.
 * New capabilities (stories, highlights, profiles) get a method here and a
 * function in igbridge.py.
 */
interface InstaloaderBridge {
    /** Puts the cookies into a fresh Instaloader session. Local only, no network. */
    suspend fun loadSession(cookies: InstagramCookies): Outcome<Unit>

    /** Asks Instagram who we are. Returns the username. */
    suspend fun verifySession(): Outcome<String>

    suspend fun clearSession()

    /** Downloads one post/reel's media into [targetDir] (created if needed). */
    suspend fun downloadPost(shortcode: String, targetDir: File): Outcome<DownloadedPost>

    suspend fun instaloaderVersion(): String
}
