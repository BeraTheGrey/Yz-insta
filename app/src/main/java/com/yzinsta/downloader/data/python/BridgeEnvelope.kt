package com.yzinsta.downloader.data.python

import com.yzinsta.downloader.data.download.DownloadedBatch
import com.yzinsta.downloader.data.download.MediaExporter
import com.yzinsta.downloader.data.download.MediaFile
import com.yzinsta.downloader.data.download.MediaKind
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import com.yzinsta.downloader.data.profile.ProfileCounters
import com.yzinsta.downloader.data.profile.ProfileInfo
import com.yzinsta.downloader.data.profile.ProfileStep
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Decodes the JSON envelope returned by every function in `igbridge.py`:
 * `{"ok": true, "data": ...}` or `{"ok": false, "error": {"code": ..., "detail": ...}}`.
 */
object BridgeEnvelope {

    fun <T> decode(raw: String?, data: (JSONObject) -> T): Outcome<T> {
        val json = try {
            JSONObject(raw ?: return Outcome.Failure(AppError.Unknown("empty bridge response")))
        } catch (e: JSONException) {
            return Outcome.Failure(AppError.Unknown("malformed bridge response: ${e.message}"))
        }
        if (json.optBoolean("ok", false)) {
            return try {
                Outcome.Success(data(json))
            } catch (e: JSONException) {
                Outcome.Failure(AppError.Unknown("unexpected bridge data: ${e.message}"))
            }
        }
        val error = json.optJSONObject("error")
        return Outcome.Failure(
            errorFor(error?.optString("code").orEmpty(), error?.optString("detail"))
        )
    }

    /** Parses the `data` of `download_post` / `download_story` / `download_highlight`. */
    fun downloadedBatch(json: JSONObject): DownloadedBatch {
        val data = json.getJSONObject("data")
        val files = data.getJSONArray("files")
        val owner = data.optString("owner").takeIf { it.isNotEmpty() && !data.isNull("owner") }
        return DownloadedBatch(id = data.getString("id"), owner = owner, files = mediaFiles(files, owner))
    }

    private fun mediaFiles(files: JSONArray, owner: String?) = List(files.length()) { i ->
        val f = files.getJSONObject(i)
        MediaFile(
            file = File(f.getString("path")),
            displayName = f.getString("name"),
            kind = if (f.getString("kind") == "video") MediaKind.VIDEO else MediaKind.IMAGE,
            mimeType = f.getString("mime"),
            album = f.optString("owner").ifEmpty { owner ?: MediaExporter.UNKNOWN_ALBUM },
        )
    }

    /** `data` of `profile_info` / `profile_start`. */
    fun profileInfo(json: JSONObject): ProfileInfo {
        val data = json.getJSONObject("data")
        return ProfileInfo(
            username = data.getString("username"),
            fullName = data.optString("full_name"),
            posts = data.optInt("posts"),
            isPrivate = data.optBoolean("is_private"),
            followed = data.optBoolean("followed"),
        )
    }

    /** `data` of `profile_next`. */
    fun profileStep(json: JSONObject): ProfileStep {
        val data = json.getJSONObject("data")
        val counters = ProfileCounters(data.optInt("downloaded"), data.optInt("skipped"), data.optInt("failed"))
        if (data.getString("status") == "finished") return ProfileStep.Finished(counters)
        val at = ProfileStep.Position(
            section = data.getString("section"),
            position = data.optInt("position"),
            expected = if (data.isNull("expected") || !data.has("expected")) null else data.getInt("expected"),
        )
        val id = data.getString("id")
        return when (val status = data.getString("status")) {
            "downloaded" -> ProfileStep.Downloaded(
                counters, at, id, data.optString("kind"), mediaFiles(data.getJSONArray("files"), null),
            )
            "skipped" -> ProfileStep.Skipped(counters, at, id)
            "failed" -> ProfileStep.Failed(counters, at, id, data.optString("detail").ifEmpty { null })
            else -> throw JSONException("unknown profile step status $status")
        }
    }

    /** Mirrors `ErrorCode` in igbridge.py. */
    fun errorFor(code: String, detail: String?): AppError = when (code) {
        "INVALID_COOKIES" -> AppError.InvalidCookies(detail)
        "NOT_LOGGED_IN" -> AppError.NotLoggedIn
        "SESSION_EXPIRED" -> AppError.SessionExpired
        "CHALLENGE_REQUIRED" -> AppError.ChallengeRequired
        "PRIVATE" -> AppError.PrivateOrNoAccess
        "NOT_FOUND" -> AppError.NotFound
        "RATE_LIMITED" -> AppError.RateLimited
        "NETWORK" -> AppError.Network
        else -> AppError.Unknown(detail?.takeIf { it.isNotBlank() } ?: code.ifBlank { null })
    }
}
