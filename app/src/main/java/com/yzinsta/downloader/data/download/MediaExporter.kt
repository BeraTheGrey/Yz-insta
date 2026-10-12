package com.yzinsta.downloader.data.download

/** Publishes a downloaded file to the shared gallery. */
interface MediaExporter {
    /** Throws on failure; returns a string identifying the saved item (content URI or path). */
    suspend fun export(media: MediaFile): String

    companion object {
        const val ALBUM = "InstaloaderApp"
    }
}
