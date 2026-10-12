package com.yzinsta.downloader.data.download

import java.io.File

enum class MediaKind { IMAGE, VIDEO }

/**
 * A downloaded file in the app cache. [displayName] is its gallery name (owner_date...),
 * [album] the per-account folder it goes into (the owner's username).
 */
data class MediaFile(
    val file: File,
    val displayName: String,
    val kind: MediaKind,
    val mimeType: String,
    val album: String = MediaExporter.UNKNOWN_ALBUM,
)

/** Result of one download call: a post, a story item set or a highlight. */
data class DownloadedBatch(val id: String, val owner: String?, val files: List<MediaFile>)
