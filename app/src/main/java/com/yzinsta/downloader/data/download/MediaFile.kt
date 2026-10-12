package com.yzinsta.downloader.data.download

import java.io.File

enum class MediaKind { IMAGE, VIDEO }

data class MediaFile(val file: File, val displayName: String, val kind: MediaKind, val mimeType: String)

data class DownloadedPost(val shortcode: String, val owner: String?, val files: List<MediaFile>)
