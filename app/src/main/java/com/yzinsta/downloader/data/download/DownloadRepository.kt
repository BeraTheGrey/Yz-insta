package com.yzinsta.downloader.data.download

import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.CancellationException
import java.io.File

sealed interface DownloadStage {
    data object Fetching : DownloadStage
    data class Saving(val done: Int, val total: Int) : DownloadStage
}

data class DownloadSummary(val shortcode: String, val owner: String?, val images: Int, val videos: Int)

/**
 * Link -> Instaloader download into the app cache -> gallery. Independent of UI so a
 * foreground service can drive it later (profiles, stories).
 */
class DownloadRepository(
    private val bridge: InstaloaderBridge,
    private val exporter: MediaExporter,
    private val cacheRoot: File,
) {
    private val workRoot get() = File(cacheRoot, "downloads")

    /** Leftovers from a crashed/killed download. */
    fun cleanUpStale() {
        workRoot.deleteRecursively()
    }

    suspend fun downloadPost(
        link: String,
        onStage: (DownloadStage) -> Unit = {},
    ): Outcome<DownloadSummary> {
        val shortcode = ShortcodeParser.parse(link) ?: return Outcome.Failure(AppError.InvalidLink)
        val workDir = File(workRoot, "$shortcode-${System.nanoTime()}")
        try {
            onStage(DownloadStage.Fetching)
            val post = when (val result = bridge.downloadPost(shortcode, workDir)) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            post.files.forEachIndexed { index, media ->
                onStage(DownloadStage.Saving(index, post.files.size))
                try {
                    exporter.export(media)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.Failure(AppError.Storage("${media.displayName}: ${e.message}"))
                }
            }
            onStage(DownloadStage.Saving(post.files.size, post.files.size))
            return Outcome.Success(
                DownloadSummary(
                    shortcode = shortcode,
                    owner = post.owner,
                    images = post.files.count { it.kind == MediaKind.IMAGE },
                    videos = post.files.count { it.kind == MediaKind.VIDEO },
                )
            )
        } finally {
            workDir.deleteRecursively()
        }
    }
}
