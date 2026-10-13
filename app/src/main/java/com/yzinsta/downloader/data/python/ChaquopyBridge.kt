package com.yzinsta.downloader.data.python

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.yzinsta.downloader.data.download.DownloadedBatch
import com.yzinsta.downloader.data.download.InstaLink
import com.yzinsta.downloader.data.profile.ProfileInfo
import com.yzinsta.downloader.data.profile.ProfileOptions
import com.yzinsta.downloader.data.profile.ProfileStep
import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * [InstaloaderBridge] backed by `igbridge.py` running in Chaquopy.
 *
 * Python calls are blocking, so they run on [io]; the module keeps one global
 * Instaloader session, so calls are serialized with a [Mutex].
 */
class ChaquopyBridge(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : InstaloaderBridge {

    private val appContext = context.applicationContext
    private val lock = Mutex()

    private fun module() = run {
        if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
        Python.getInstance().getModule("igbridge")
    }

    private suspend fun call(function: String, vararg args: Any?): String? = lock.withLock {
        withContext(io) {
            try {
                module().callAttr(function, *args)?.toString()
            } catch (e: PyException) {
                // igbridge never lets exceptions escape on purpose; this is a bug or an import failure.
                """{"ok":false,"error":{"code":"UNKNOWN","detail":${JSONObject.quote(e.message ?: "PyException")}}}"""
            }
        }
    }

    override suspend fun loadSession(cookies: InstagramCookies): Outcome<Unit> {
        if (!cookies.isComplete) {
            return Outcome.Failure(AppError.InvalidCookies("missing cookies: ${cookies.missing.joinToString(",")}"))
        }
        return BridgeEnvelope.decode(call("load_session", cookies.toJson())) { }
    }

    override suspend fun verifySession(): Outcome<String> =
        BridgeEnvelope.decode(call("verify_session")) { it.getJSONObject("data").getString("username") }

    override suspend fun clearSession() {
        call("clear_session")
    }

    override suspend fun download(
        target: InstaLink,
        targetDir: File,
        progress: ProgressListener,
    ): Outcome<DownloadedBatch> {
        // File names use the post date in the phone's time zone.
        val tz = TimeZone.getDefault().id
        val dir = targetDir.absolutePath
        val raw = when (target) {
            is InstaLink.Post -> call("download_post", target.shortcode, dir, tz, progress)
            is InstaLink.Story -> call("download_story", target.username, target.mediaId, dir, tz, progress)
            is InstaLink.Highlight -> call("download_highlight", target.id, dir, tz, progress)
            is InstaLink.Profile -> return Outcome.Failure(AppError.Unknown("profiles download via profileStart/profileNext"))
        }
        return BridgeEnvelope.decode(raw, BridgeEnvelope::downloadedBatch)
    }

    override suspend fun profileInfo(username: String): Outcome<ProfileInfo> =
        BridgeEnvelope.decode(call("profile_info", username), BridgeEnvelope::profileInfo)

    override suspend fun profileStart(username: String, options: ProfileOptions, knownIds: Set<String>): Outcome<ProfileInfo> {
        val optionsJson = JSONObject()
            .put("stories", options.stories)
            .put("highlights", options.highlights)
            .put("posts", options.posts)
            .put("reels", options.reels)
            .put("limit", options.limit)
        return BridgeEnvelope.decode(
            call("profile_start", username, optionsJson.toString(), JSONArray(knownIds.toList()).toString()),
            BridgeEnvelope::profileInfo,
        )
    }

    override suspend fun profileNext(targetDir: File): Outcome<ProfileStep> =
        BridgeEnvelope.decode(call("profile_next", targetDir.absolutePath, TimeZone.getDefault().id), BridgeEnvelope::profileStep)

    override suspend fun profileCancel() {
        call("profile_cancel")
    }

    override suspend fun instaloaderVersion(): String = lock.withLock {
        withContext(io) { module().callAttr("version").toString() }
    }
}
