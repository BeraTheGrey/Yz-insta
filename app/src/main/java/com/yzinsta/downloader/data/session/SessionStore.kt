package com.yzinsta.downloader.data.session

data class StoredSession(val cookies: InstagramCookies, val username: String?)

/** Persists the Instagram session between app launches. */
interface SessionStore {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()
}
