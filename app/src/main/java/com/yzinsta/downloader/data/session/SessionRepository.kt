package com.yzinsta.downloader.data.session

import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.domain.AppError
import com.yzinsta.downloader.domain.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SessionState {
    /** Reading the stored session / checking it with Instagram. */
    data object Checking : SessionState

    data class LoggedOut(val reason: AppError? = null) : SessionState

    /**
     * Cookies are loaded into Instaloader. [warning] is set when Instagram could
     * not confirm the session for a transient reason (network, rate limit); the
     * session is kept and the user can still try to download.
     */
    data class LoggedIn(val username: String?, val warning: AppError? = null) : SessionState
}

/** Owns the login session: WebView cookies -> encrypted store -> Instaloader. */
class SessionRepository(
    private val bridge: InstaloaderBridge,
    private val store: SessionStore,
    private val webCookies: WebCookieJar,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Checking)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** App start: reuse the stored session if there is one. */
    suspend fun restore() = mutex.withLock {
        _state.value = SessionState.Checking
        val stored = store.load()
        _state.value = if (stored == null) SessionState.LoggedOut() else activate(stored.cookies, stored.username)
    }

    /** Take over cookies from the login WebView (or pasted by the user) and verify them. */
    suspend fun login(cookies: InstagramCookies) = mutex.withLock {
        _state.value = SessionState.Checking
        _state.value = activate(cookies, knownUsername = null)
    }

    /** Re-check the current session (e.g. "retry" after a network warning). */
    suspend fun reverify() {
        val stored = mutex.withLock { store.load() }
        if (stored == null) logout() else mutex.withLock {
            _state.value = SessionState.Checking
            _state.value = activate(stored.cookies, stored.username)
        }
    }

    suspend fun logout() = mutex.withLock {
        forget()
        _state.value = SessionState.LoggedOut()
    }

    /** A download found out that Instagram no longer accepts the session. */
    suspend fun invalidate(reason: AppError) = mutex.withLock {
        forget()
        _state.value = SessionState.LoggedOut(reason)
    }

    private suspend fun activate(cookies: InstagramCookies, knownUsername: String?): SessionState {
        val loaded = bridge.loadSession(cookies)
        if (loaded is Outcome.Failure) {
            forget()
            return SessionState.LoggedOut(loaded.error)
        }
        return when (val verified = bridge.verifySession()) {
            is Outcome.Success -> {
                store.save(StoredSession(cookies, verified.value))
                SessionState.LoggedIn(verified.value)
            }
            is Outcome.Failure -> {
                val error = verified.error
                if (error.requiresLogin) {
                    forget()
                    SessionState.LoggedOut(error)
                } else {
                    store.save(StoredSession(cookies, knownUsername))
                    SessionState.LoggedIn(knownUsername, warning = error)
                }
            }
        }
    }

    private suspend fun forget() {
        store.clear()
        bridge.clearSession()
        webCookies.clear()
    }
}
