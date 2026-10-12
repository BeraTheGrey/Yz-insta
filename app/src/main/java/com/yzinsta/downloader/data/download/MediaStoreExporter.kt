package com.yzinsta.downloader.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Copies files into Pictures/InstaloaderApp/<username>/, photos and videos together so each
 * account is one album. (MediaStore allows videos under Pictures/.)
 * API 29+: MediaStore with RELATIVE_PATH/IS_PENDING, no permission needed.
 * API 26-28: plain file copy into the public directory (needs WRITE_EXTERNAL_STORAGE)
 * followed by a media scan.
 */
class MediaStoreExporter(context: Context) : MediaExporter {

    private val appContext = context.applicationContext

    override suspend fun export(media: MediaFile): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportScoped(media) else exportLegacy(media)
    }

    private fun exportScoped(media: MediaFile): String {
        val resolver = appContext.contentResolver
        val collection = when (media.kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, media.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${relativeAlbum(media)}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                media.file.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("Cannot open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(media: MediaFile): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), relativeAlbum(media))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val target = uniqueFile(dir, media.displayName)
        media.file.copyTo(target)
        MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), arrayOf(media.mimeType), null)
        return target.absolutePath
    }

    /** "InstaloaderApp/<username>"; the name comes from Python but is sanitized again here. */
    private fun relativeAlbum(media: MediaFile): String {
        val album = media.album.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifEmpty { MediaExporter.UNKNOWN_ALBUM }
        return "${MediaExporter.ROOT}/$album"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
            n++
        }
        return candidate
    }
}
