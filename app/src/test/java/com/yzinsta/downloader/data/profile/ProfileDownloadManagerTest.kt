package com.yzinsta.downloader.data.profile

import com.yzinsta.downloader.data.download.DownloadedBatch
import com.yzinsta.downloader.data.download.InstaLink
import com.yzinsta.downloader.data.download.MediaExporter
import com.yzinsta.downloader.data.download.MediaFile
import com.yzinsta.downloader.data.download.MediaKind
import com.yzinsta.downloader.data.history.DownloadHistory
import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.data.python.ProgressListener
import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.data.session.SessionRepository
import com.yzinsta.downloader.data.session.SessionState
import com.yzinsta.downloader.data.session.SessionStore
import com.yzinsta.downloader.data.session.StoredSession
import com.yzinsta.downloader.data.session.WebCookieJar
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileDownloadManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val at = ProfileStep.Position("posts", 1, 2)

    /** Bridge that replays scripted profile_next results. */
    private inner class ScriptedBridge(
        val start: Outcome<ProfileInfo> = Outcome.Success(ProfileInfo("zeynep", "", 2, false, true)),
        steps: List<(File) -> Outcome<ProfileStep>>,
    ) : InstaloaderBridge {
        val queue = ArrayDeque(steps)
        var known: Set<String>? = null
        var cancelled = 0
        var nextCalls = 0
        override suspend fun profileStart(username: String, options: ProfileOptions, knownIds: Set<String>): Outcome<ProfileInfo> {
            known = knownIds
            return start
        }
        override suspend fun profileNext(targetDir: File): Outcome<ProfileStep> {
            nextCalls++
            return queue.removeFirst()(targetDir)
        }
        override suspend fun profileCancel() { cancelled++ }
        override suspend fun profileInfo(username: String) = start
        override suspend fun loadSession(cookies: InstagramCookies) = Outcome.Success(Unit)
        override suspend fun verifySession() = Outcome.Success("me")
        override suspend fun clearSession() {}
        override suspend fun instaloaderVersion() = "test"
        override suspend fun download(target: InstaLink, targetDir: File, progress: ProgressListener): Outcome<DownloadedBatch> =
            throw UnsupportedOperationException()
    }

    private fun downloaded(id: String, done: Int) = { dir: File ->
        dir.mkdirs()
        val file = File(dir, "$id.jpg").apply { writeText("x") }
        Outcome.Success(
            ProfileStep.Downloaded(ProfileCounters(done, 0, 0), at, id, "post",
                listOf(MediaFile(file, "zeynep_$id.jpg", MediaKind.IMAGE, "image/jpeg", "zeynep")))
        )
    }

    private fun finished(done: Int) = { _: File -> Outcome.Success(ProfileStep.Finished(ProfileCounters(done, 0, 0))) }
    private fun error(e: AppError) = { _: File -> Outcome.Failure(e) }

    private class Exporter : MediaExporter {
        val exported = mutableListOf<String>()
        override suspend fun export(media: MediaFile): String {
            check(media.file.isFile)
            exported += "${media.album}/${media.displayName}"
            return "ok"
        }
    }

    private class History(val known: Set<String> = emptySet()) : DownloadHistory {
        val records = mutableListOf<String>()
        override suspend fun record(owner: String, itemId: String, kind: String, files: Int) { records += itemId }
        override suspend fun knownIds(owner: String) = known
    }

    private class Store : SessionStore {
        var stored: StoredSession? = null
        override suspend fun load() = stored
        override suspend fun save(session: StoredSession) { stored = session }
        override suspend fun clear() { stored = null }
    }

    private class Web : WebCookieJar {
        override suspend fun instagramCookies() = InstagramCookies(emptyMap())
        override suspend fun clear() {}
    }

    private fun manager(bridge: ScriptedBridge, exporter: Exporter = Exporter(), history: History = History()) =
        ProfileDownloadManager(
            bridge, exporter, history, SessionRepository(bridge, Store(), Web()),
            File(tmp.root, "profile"), backoffMinutes = listOf(3, 6), pauseBetweenItemsMs = 10,
        )

    @Test
    fun `downloads, exports, records and finishes`() = runTest {
        val bridge = ScriptedBridge(steps = listOf(downloaded("A", 1), downloaded("B", 2), finished(2)))
        val exporter = Exporter()
        val history = History(known = setOf("OLD"))
        val m = manager(bridge, exporter, history)

        m.run("zeynep", ProfileOptions())

        assertEquals(setOf("OLD"), bridge.known)
        assertEquals(listOf("zeynep/zeynep_A.jpg", "zeynep/zeynep_B.jpg"), exporter.exported)
        assertEquals(listOf("A", "B"), history.records)
        assertEquals(ProfileJobState.Finished("zeynep", ProfileCounters(2, 0, 0)), m.state.value)
        assertEquals(1, bridge.cancelled)
        assertTrue(File(tmp.root, "profile").listFiles().isNullOrEmpty())
    }

    @Test
    fun `rate limit is waited out and the job continues`() = runTest {
        val bridge = ScriptedBridge(steps = listOf(
            downloaded("A", 1), error(AppError.RateLimited), error(AppError.RateLimited), downloaded("B", 2), finished(2),
        ))
        val m = manager(bridge)
        val job = launch { m.run("zeynep", ProfileOptions()) }
        runCurrent()
        advanceTimeBy(20)  // past the pause after A; now waiting 3 min
        val waiting = m.state.value as ProfileJobState.Running
        assertEquals(AppError.RateLimited, waiting.waitReason)
        advanceTimeBy(3 * 60_000L)  // first wait over, second limit -> 6 min
        assertEquals(AppError.RateLimited, (m.state.value as ProfileJobState.Running).waitReason)
        advanceTimeBy(6 * 60_000L + 100)
        job.join()
        assertEquals(ProfileJobState.Finished("zeynep", ProfileCounters(2, 0, 0)), m.state.value)
        assertTrue(currentTime >= 9 * 60_000L)
    }

    @Test
    fun `gives up after the backoff list`() = runTest {
        val bridge = ScriptedBridge(steps = List(3) { error(AppError.Network) })
        val m = manager(bridge)
        m.run("zeynep", ProfileOptions())
        assertEquals(ProfileJobState.Failed("zeynep", AppError.Network, ProfileCounters()), m.state.value)
        assertEquals(3, bridge.nextCalls)
    }

    @Test
    fun `expired session stops the job and logs out`() = runTest {
        val bridge = ScriptedBridge(steps = listOf(error(AppError.SessionExpired)))
        val m = manager(bridge)
        m.run("zeynep", ProfileOptions())
        assertTrue((m.state.value as ProfileJobState.Failed).error is AppError.SessionExpired)
    }

    @Test
    fun `private profile fails at start`() = runTest {
        val bridge = ScriptedBridge(start = Outcome.Failure(AppError.PrivateOrNoAccess), steps = emptyList())
        val m = manager(bridge)
        m.run("zeynep", ProfileOptions())
        assertEquals(ProfileJobState.Failed("zeynep", AppError.PrivateOrNoAccess, ProfileCounters()), m.state.value)
        assertEquals(1, bridge.cancelled)
    }

    @Test
    fun `stop cancels cleanly`() = runTest {
        val bridge = ScriptedBridge(steps = listOf(downloaded("A", 1), error(AppError.RateLimited)))
        val m = manager(bridge)
        val job = launch { m.run("zeynep", ProfileOptions()) }
        advanceTimeBy(60_000)
        job.cancel()
        job.join()
        assertEquals(ProfileJobState.Cancelled("zeynep", ProfileCounters(1, 0, 0)), m.state.value)
        assertEquals(1, bridge.cancelled)
        m.dismiss()
        assertEquals(ProfileJobState.Idle, m.state.value)
    }
}
