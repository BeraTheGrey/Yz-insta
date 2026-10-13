package com.yzinsta.downloader.data.history

/** Items already downloaded, so profile downloads can skip them (and a history screen later). */
interface DownloadHistory {
    /** [itemId] is a post shortcode or a story media id. */
    suspend fun record(owner: String, itemId: String, kind: String, files: Int)

    suspend fun knownIds(owner: String): Set<String>
}
