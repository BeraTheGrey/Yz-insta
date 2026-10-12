package com.yzinsta.downloader.data.session

import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryTest {

    private val cookies = InstagramCookies(mapOf("sessionid" to "s", "csrftoken" to "c", "ds_user_id" to "1"))

    private class FakeBridge : InstaloaderBridge {
        var loadResult: Outcome<Unit> = Outcome.Success(Unit)
        var verifyResult: Outcome<String> = Outcome.Success("alice")
        var loaded: InstagramCookies? = null
        var cleared = 0
        override suspend fun loadSession(cookies: InstagramCookies): Outcome<Unit> {
            loaded = cookies
            return loadResult
        }
        override suspend fun verifySession() = verifyResult
        override suspend fun clearSession() { cleared++ }
        override suspend fun instaloaderVersion() = "test"
        override suspend fun downloadPost(shortcode: String, targetDir: java.io.File) =
            throw UnsupportedOperationException()
    }

    private class FakeStore(var stored: StoredSession? = null) : SessionStore {
        override suspend fun load() = stored
        override suspend fun save(session: StoredSession) { stored = session }
        override suspend fun clear() { stored = null }
    }

    private class FakeWebCookies : WebCookieJar {
        var cleared = 0
        override suspend fun instagramCookies() = InstagramCookies(emptyMap())
        override suspend fun clear() { cleared++ }
    }

    private val bridge = FakeBridge()
    private val store = FakeStore()
    private val web = FakeWebCookies()
    private val repo = SessionRepository(bridge, store, web)

    @Test
    fun `no stored session means logged out`() = runTest {
        repo.restore()
        assertEquals(SessionState.LoggedOut(), repo.state.value)
        assertNull(bridge.loaded)
    }

    @Test
    fun `web login verifies and stores cookies with username`() = runTest {
        repo.login(cookies)
        assertEquals(SessionState.LoggedIn("alice"), repo.state.value)
        assertEquals(cookies, bridge.loaded)
        assertEquals(StoredSession(cookies, "alice"), store.stored)
    }

    @Test
    fun `restore reuses stored cookies without web login`() = runTest {
        store.stored = StoredSession(cookies, "alice")
        repo.restore()
        assertEquals(SessionState.LoggedIn("alice"), repo.state.value)
        assertEquals(cookies, bridge.loaded)
    }

    @Test
    fun `expired session is wiped everywhere`() = runTest {
        store.stored = StoredSession(cookies, "alice")
        bridge.verifyResult = Outcome.Failure(AppError.SessionExpired)
        repo.restore()
        assertEquals(SessionState.LoggedOut(AppError.SessionExpired), repo.state.value)
        assertNull(store.stored)
        assertEquals(1, bridge.cleared)
        assertEquals(1, web.cleared)
    }

    @Test
    fun `network failure keeps the session with a warning`() = runTest {
        store.stored = StoredSession(cookies, "alice")
        bridge.verifyResult = Outcome.Failure(AppError.Network)
        repo.restore()
        assertEquals(SessionState.LoggedIn("alice", warning = AppError.Network), repo.state.value)
        assertEquals(StoredSession(cookies, "alice"), store.stored)
        assertEquals(0, web.cleared)
    }

    @Test
    fun `rate limit during web login still stores the fresh cookies`() = runTest {
        bridge.verifyResult = Outcome.Failure(AppError.RateLimited)
        repo.login(cookies)
        assertEquals(SessionState.LoggedIn(null, warning = AppError.RateLimited), repo.state.value)
        assertEquals(StoredSession(cookies, null), store.stored)
    }

    @Test
    fun `challenge forces a new login`() = runTest {
        bridge.verifyResult = Outcome.Failure(AppError.ChallengeRequired)
        repo.login(cookies)
        assertEquals(SessionState.LoggedOut(AppError.ChallengeRequired), repo.state.value)
        assertNull(store.stored)
    }

    @Test
    fun `cookies rejected by python are not stored`() = runTest {
        bridge.loadResult = Outcome.Failure(AppError.InvalidCookies("missing"))
        repo.login(cookies)
        assertTrue((repo.state.value as SessionState.LoggedOut).reason is AppError.InvalidCookies)
        assertNull(store.stored)
    }

    @Test
    fun `logout clears store, python session and webview`() = runTest {
        repo.login(cookies)
        repo.logout()
        assertEquals(SessionState.LoggedOut(), repo.state.value)
        assertNull(store.stored)
        assertEquals(1, bridge.cleared)
        assertEquals(1, web.cleared)
    }

    @Test
    fun `invalidate from a download logs out with reason`() = runTest {
        repo.login(cookies)
        repo.invalidate(AppError.SessionExpired)
        assertEquals(SessionState.LoggedOut(AppError.SessionExpired), repo.state.value)
        assertNull(store.stored)
    }
}
