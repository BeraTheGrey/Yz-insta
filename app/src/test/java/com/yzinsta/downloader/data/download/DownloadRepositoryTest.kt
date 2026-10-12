package com.yzinsta.downloader.data.download

import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloadRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private inner class FakeBridge(val result: (String, File) -> Outcome<DownloadedPost>) : InstaloaderBridge {
        var calledWith: String? = null
        override suspend fun loadSession(cookies: InstagramCookies) = Outcome.Success(Unit)
        override suspend fun verifySession() = Outcome.Success("alice")
        override suspend fun clearSession() {}
        override suspend fun instaloaderVersion() = "test"
        override suspend fun downloadPost(shortcode: String, targetDir: File): Outcome<DownloadedPost> {
            calledWith = shortcode
            return result(shortcode, targetDir)
        }
    }

    private class FakeExporter(val failOn: String? = null) : MediaExporter {
        val exported = mutableListOf<String>()
        override suspend fun export(media: MediaFile): String {
            if (media.displayName == failOn) throw IOException("disk full")
            check(media.file.isFile) { "file must still exist while exporting" }
            exported += media.displayName
            return "content://${media.displayName}"
        }
    }

    private fun writePost(shortcode: String, dir: File): Outcome<DownloadedPost> {
        dir.mkdirs()
        val files = listOf("${shortcode}_1.jpg" to MediaKind.IMAGE, "${shortcode}_2.mp4" to MediaKind.VIDEO)
            .map { (name, kind) ->
                MediaFile(File(dir, name).apply { writeText("x") }, name, kind,
                    if (kind == MediaKind.IMAGE) "image/jpeg" else "video/mp4")
            }
        return Outcome.Success(DownloadedPost(shortcode, "bob", files))
    }

    @Test
    fun `downloads, exports everything and cleans the cache`() = runTest {
        val bridge = FakeBridge(::writePost)
        val exporter = FakeExporter()
        val repo = DownloadRepository(bridge, exporter, tmp.root)
        val stages = mutableListOf<DownloadStage>()

        val result = repo.downloadPost("https://www.instagram.com/p/ABCDEFG/?igsh=x") { stages += it }

        assertEquals(Outcome.Success(DownloadSummary("ABCDEFG", "bob", images = 1, videos = 1)), result)
        assertEquals("ABCDEFG", bridge.calledWith)
        assertEquals(listOf("ABCDEFG_1.jpg", "ABCDEFG_2.mp4"), exporter.exported)
        assertEquals(
            listOf(DownloadStage.Fetching, DownloadStage.Saving(0, 2), DownloadStage.Saving(1, 2), DownloadStage.Saving(2, 2)),
            stages,
        )
        assertTrue(File(tmp.root, "downloads").listFiles().isNullOrEmpty())
    }

    @Test
    fun `invalid link never reaches python`() = runTest {
        val bridge = FakeBridge(::writePost)
        val result = DownloadRepository(bridge, FakeExporter(), tmp.root).downloadPost("not a link")
        assertEquals(Outcome.Failure(AppError.InvalidLink), result)
        assertEquals(null, bridge.calledWith)
    }

    @Test
    fun `python errors are passed through`() = runTest {
        val bridge = FakeBridge { _, _ -> Outcome.Failure(AppError.PrivateOrNoAccess) }
        val result = DownloadRepository(bridge, FakeExporter(), tmp.root)
            .downloadPost("https://www.instagram.com/p/ABCDEFG/")
        assertEquals(Outcome.Failure(AppError.PrivateOrNoAccess), result)
    }

    @Test
    fun `export failure is a storage error and the cache is still cleaned`() = runTest {
        val repo = DownloadRepository(FakeBridge(::writePost), FakeExporter(failOn = "ABCDEFG_2.mp4"), tmp.root)
        val result = repo.downloadPost("https://www.instagram.com/p/ABCDEFG/")
        assertTrue(result is Outcome.Failure && result.error is AppError.Storage)
        assertTrue(File(tmp.root, "downloads").listFiles().isNullOrEmpty())
    }

    @Test
    fun `stale work dirs are removed`() {
        val stale = File(tmp.root, "downloads/OLD-1").apply { mkdirs() }
        DownloadRepository(FakeBridge(::writePost), FakeExporter(), tmp.root).cleanUpStale()
        assertFalse(stale.exists())
    }
}
