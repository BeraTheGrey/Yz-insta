package com.yzinsta.downloader.data.python

import com.yzinsta.downloader.data.download.DownloadedBatch
import com.yzinsta.downloader.data.download.InstaLink
import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.domain.Outcome
import java.io.File

/** Called from the Python thread as items finish; keep implementations cheap and thread-safe. */
fun interface ProgressListener {
    fun onProgress(done: Int, total: Int)
}

/**
 * Everything the app asks Instaloader to do. Implementations must be main-safe.
 * New capabilities get a method here and a function in igbridge.py.
 */
interface InstaloaderBridge {
    /** Puts the cookies into a fresh Instaloader session. Local only, no network. */
    suspend fun loadSession(cookies: InstagramCookies): Outcome<Unit>

    /** Asks Instagram who we are. Returns the username. */
    suspend fun verifySession(): Outcome<String>

    suspend fun clearSession()

    /** Downloads [target]'s media into [targetDir] (created if needed). */
    suspend fun download(
        target: InstaLink,
        targetDir: File,
        progress: ProgressListener = ProgressListener { _, _ -> },
    ): Outcome<DownloadedBatch>

    suspend fun instaloaderVersion(): String
}
