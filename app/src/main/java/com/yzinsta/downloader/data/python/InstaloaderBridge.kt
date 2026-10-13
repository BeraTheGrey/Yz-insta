package com.yzinsta.downloader.data.python

import com.yzinsta.downloader.data.download.DownloadedBatch
import com.yzinsta.downloader.data.download.InstaLink
import com.yzinsta.downloader.data.profile.ProfileInfo
import com.yzinsta.downloader.data.profile.ProfileOptions
import com.yzinsta.downloader.data.profile.ProfileStep
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

    suspend fun profileInfo(username: String): Outcome<ProfileInfo>

    /** Plans a profile download; [knownIds] (shortcodes / story media ids) are skipped. */
    suspend fun profileStart(username: String, options: ProfileOptions, knownIds: Set<String>): Outcome<ProfileInfo>

    /** Downloads the next item of the planned profile job into [targetDir]. */
    suspend fun profileNext(targetDir: File): Outcome<ProfileStep>

    suspend fun profileCancel()

    suspend fun instaloaderVersion(): String
}
