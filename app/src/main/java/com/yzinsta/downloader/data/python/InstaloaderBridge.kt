package com.yzinsta.downloader.data.python

import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.domain.Outcome

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

    suspend fun instaloaderVersion(): String
}
