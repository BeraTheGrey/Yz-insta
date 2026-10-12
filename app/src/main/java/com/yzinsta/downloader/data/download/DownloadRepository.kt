package com.yzinsta.downloader.data.download

import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.CancellationException
import java.io.File

sealed interface DownloadStage {
    data object Fetching : DownloadStage
    /** Instaloader finished [done] of [total] items (story items, posts). */
    data class Downloading(val done: Int, val total: Int) : DownloadStage
    data class Saving(val done: Int, val total: Int) : DownloadStage
}

data class DownloadSummary(val id: String, val owner: String?, val images: Int, val videos: Int)

/**
 * Link -> Instaloader download into the app cache -> gallery. Independent of UI so a
 * foreground service can drive it later (profiles).
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

    suspend fun download(
        link: String,
        onStage: (DownloadStage) -> Unit = {},
    ): Outcome<DownloadSummary> {
        val target = LinkParser.parse(link) ?: return Outcome.Failure(AppError.InvalidLink)
        val workDir = File(workRoot, "${System.nanoTime()}")
        try {
            onStage(DownloadStage.Fetching)
            val batch = when (
                val result = bridge.download(target, workDir) { done, total ->
                    // Single posts report 0/1 -> 1/1; only show counts when there is more than one item.
                    if (total > 1) onStage(DownloadStage.Downloading(done, total))
                }
            ) {
                is Outcome.Failure -> return result
                is Outcome.Success -> result.value
            }
            batch.files.forEachIndexed { index, media ->
                onStage(DownloadStage.Saving(index, batch.files.size))
                try {
                    exporter.export(media)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.Failure(AppError.Storage("${media.displayName}: ${e.message}"))
                }
            }
            onStage(DownloadStage.Saving(batch.files.size, batch.files.size))
            return Outcome.Success(
                DownloadSummary(
                    id = batch.id,
                    owner = batch.owner,
                    images = batch.files.count { it.kind == MediaKind.IMAGE },
                    videos = batch.files.count { it.kind == MediaKind.VIDEO },
                )
            )
        } finally {
            workDir.deleteRecursively()
        }
    }
}
