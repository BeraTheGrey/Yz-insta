package com.yzinsta.downloader.data.download

import java.io.File

enum class MediaKind { IMAGE, VIDEO }

/** A downloaded file in the app cache; [displayName] is its gallery name (owner_date_...). */
data class MediaFile(val file: File, val displayName: String, val kind: MediaKind, val mimeType: String)

/** Result of one download call: a post, a story item set or a highlight. */
data class DownloadedBatch(val id: String, val owner: String?, val files: List<MediaFile>)
