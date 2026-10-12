package com.yzinsta.downloader.data.python

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.yzinsta.downloader.data.download.DownloadedPost
import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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
                """{"ok":false,"error":{"code":"UNKNOWN","detail":${org.json.JSONObject.quote(e.message ?: "PyException")}}}"""
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

    override suspend fun downloadPost(shortcode: String, targetDir: File): Outcome<DownloadedPost> =
        BridgeEnvelope.decode(call("download_post", shortcode, targetDir.absolutePath), BridgeEnvelope::downloadedPost)

    override suspend fun instaloaderVersion(): String = lock.withLock {
        withContext(io) { module().callAttr("version").toString() }
    }
}
