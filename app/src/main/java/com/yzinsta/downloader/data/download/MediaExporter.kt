package com.yzinsta.downloader.data.download

/** Publishes a downloaded file to the shared gallery. */
interface MediaExporter {
    /** Throws on failure; returns a string identifying the saved item (content URI or path). */
    suspend fun export(media: MediaFile): String

    companion object {
        /** Everything goes under Pictures/InstaloaderApp/<username>/ (one folder per account). */
        const val ROOT = "InstaloaderApp"
        const val UNKNOWN_ALBUM = "instagram"
    }
}
