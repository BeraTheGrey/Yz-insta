package com.yzinsta.downloader.data.download

import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.data.python.ProgressListener
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

    private class FakeBridge(
        val result: (InstaLink, File, ProgressListener) -> Outcome<DownloadedBatch>,
    ) : InstaloaderBridge {
        var calledWith: InstaLink? = null
        override suspend fun loadSession(cookies: InstagramCookies) = Outcome.Success(Unit)
        override suspend fun verifySession() = Outcome.Success("alice")
        override suspend fun clearSession() {}
        override suspend fun instaloaderVersion() = "test"
        override suspend fun profileInfo(username: String) =
            throw UnsupportedOperationException()
        override suspend fun profileStart(
            username: String,
            options: com.yzinsta.downloader.data.profile.ProfileOptions,
            knownIds: Set<String>,
        ) = throw UnsupportedOperationException()
        override suspend fun profileNext(targetDir: java.io.File) = throw UnsupportedOperationException()
        override suspend fun profileCancel() {}
        override suspend fun download(target: InstaLink, targetDir: File, progress: ProgressListener): Outcome<DownloadedBatch> {
            calledWith = target
            return result(target, targetDir, progress)
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

    private fun write(dir: File, vararg names: String): List<MediaFile> {
        dir.mkdirs()
        return names.map { name ->
            val video = name.endsWith(".mp4")
            MediaFile(File(dir, name).apply { writeText("x") }, "bob_2026-10-12_21-30-05_$name",
                if (video) MediaKind.VIDEO else MediaKind.IMAGE, if (video) "video/mp4" else "image/jpeg")
        }
    }

    private fun post(target: InstaLink, dir: File, progress: ProgressListener): Outcome<DownloadedBatch> {
        progress.onProgress(0, 1)
        progress.onProgress(1, 1)
        return Outcome.Success(DownloadedBatch("ABCDEFG", "bob", write(dir, "1.jpg", "2.mp4")))
    }

    @Test
    fun `post is downloaded, exported with gallery names and the cache cleaned`() = runTest {
        val bridge = FakeBridge(::post)
        val exporter = FakeExporter()
        val stages = mutableListOf<DownloadStage>()

        val result = DownloadRepository(bridge, exporter, tmp.root)
            .download("https://www.instagram.com/p/ABCDEFG/?igsh=x") { stages += it }

        assertEquals(Outcome.Success(DownloadSummary("ABCDEFG", "bob", images = 1, videos = 1)), result)
        assertEquals(InstaLink.Post("ABCDEFG"), bridge.calledWith)
        assertEquals(listOf("bob_2026-10-12_21-30-05_1.jpg", "bob_2026-10-12_21-30-05_2.mp4"), exporter.exported)
        // a single post reports no "1/1" item counter
        assertEquals(
            listOf(DownloadStage.Fetching, DownloadStage.Saving(0, 2), DownloadStage.Saving(1, 2), DownloadStage.Saving(2, 2)),
            stages,
        )
        assertTrue(File(tmp.root, "downloads").listFiles().isNullOrEmpty())
    }

    @Test
    fun `highlight reports per-item progress`() = runTest {
        val bridge = FakeBridge { _, dir, progress ->
            (0..3).forEach { progress.onProgress(it, 3) }
            Outcome.Success(DownloadedBatch("179", "zeynep", write(dir, "a.jpg", "b.mp4", "c.mp4")))
        }
        val stages = mutableListOf<DownloadStage>()
        val result = DownloadRepository(bridge, FakeExporter(), tmp.root)
            .download("https://www.instagram.com/stories/highlights/179/") { stages += it }

        assertEquals(InstaLink.Highlight("179"), bridge.calledWith)
        assertEquals(Outcome.Success(DownloadSummary("179", "zeynep", images = 1, videos = 2)), result)
        assertEquals((0..3).map { DownloadStage.Downloading(it, 3) }, stages.filterIsInstance<DownloadStage.Downloading>())
    }

    @Test
    fun `story link reaches the bridge as a story`() = runTest {
        val bridge = FakeBridge { _, dir, _ -> Outcome.Success(DownloadedBatch("1", "z", write(dir, "s.mp4"))) }
        DownloadRepository(bridge, FakeExporter(), tmp.root).download("https://www.instagram.com/stories/z/123456/")
        assertEquals(InstaLink.Story("z", "123456"), bridge.calledWith)
    }

    @Test
    fun `single downloads are recorded in the history`() = runTest {
        val history = FakeHistory()
        DownloadRepository(FakeBridge(::post), FakeExporter(), tmp.root, history)
            .download("https://www.instagram.com/p/ABCDEFG/")
        assertEquals(listOf("bob/ABCDEFG/post/2"), history.records)
    }

    private class FakeHistory : com.yzinsta.downloader.data.history.DownloadHistory {
        val records = mutableListOf<String>()
        override suspend fun record(owner: String, itemId: String, kind: String, files: Int) {
            records += "$owner/$itemId/$kind/$files"
        }
        override suspend fun knownIds(owner: String) = emptySet<String>()
    }

    @Test
    fun `profile links are not single downloads`() = runTest {
        val bridge = FakeBridge(::post)
        val result = DownloadRepository(bridge, FakeExporter(), tmp.root).download("https://www.instagram.com/zeynep/")
        assertEquals(Outcome.Failure(AppError.InvalidLink), result)
        assertEquals(null, bridge.calledWith)
    }

    @Test
    fun `invalid link never reaches python`() = runTest {
        val bridge = FakeBridge(::post)
        val result = DownloadRepository(bridge, FakeExporter(), tmp.root).download("not a link")
        assertEquals(Outcome.Failure(AppError.InvalidLink), result)
        assertEquals(null, bridge.calledWith)
    }

    @Test
    fun `python errors are passed through`() = runTest {
        val bridge = FakeBridge { _, _, _ -> Outcome.Failure(AppError.PrivateOrNoAccess) }
        val result = DownloadRepository(bridge, FakeExporter(), tmp.root).download("https://www.instagram.com/p/ABCDEFG/")
        assertEquals(Outcome.Failure(AppError.PrivateOrNoAccess), result)
    }

    @Test
    fun `export failure is a storage error and the cache is still cleaned`() = runTest {
        val repo = DownloadRepository(FakeBridge(::post), FakeExporter(failOn = "bob_2026-10-12_21-30-05_2.mp4"), tmp.root)
        val result = repo.download("https://www.instagram.com/p/ABCDEFG/")
        assertTrue(result is Outcome.Failure && result.error is AppError.Storage)
        assertTrue(File(tmp.root, "downloads").listFiles().isNullOrEmpty())
    }

    @Test
    fun `stale work dirs are removed`() {
        val stale = File(tmp.root, "downloads/OLD-1").apply { mkdirs() }
        DownloadRepository(FakeBridge(::post), FakeExporter(), tmp.root).cleanUpStale()
        assertFalse(stale.exists())
    }
}
