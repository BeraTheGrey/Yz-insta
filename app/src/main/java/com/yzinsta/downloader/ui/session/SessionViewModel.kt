package com.yzinsta.downloader.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yzinsta.downloader.data.session.InstagramCookies
import com.yzinsta.downloader.data.session.SessionRepository
import com.yzinsta.downloader.data.session.SessionState
import com.yzinsta.downloader.data.session.WebCookieJar
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SessionViewModel(
    private val repository: SessionRepository,
    private val webCookies: WebCookieJar,
) : ViewModel() {

    val state: StateFlow<SessionState> = repository.state

    init {
        viewModelScope.launch { repository.restore() }
    }

    /**
     * Called by the login WebView after each page load; returns true once a session
     * was taken over. Verification runs in [viewModelScope] because the WebView
     * screen leaves composition as soon as the state switches to Checking.
     */
    suspend fun tryCompleteWebLogin(): Boolean {
        val cookies = webCookies.instagramCookies()
        if (!cookies.isComplete) return false
        viewModelScope.launch { repository.login(cookies) }
        return true
    }

    /** Fallback when the WebView login doesn't work: cookies pasted by the user. */
    fun loginWithPastedCookies(text: String) {
        val cookies = InstagramCookies.fromUserInput(text)
        viewModelScope.launch { repository.login(cookies) }
    }

    fun clearWebCookiesForFreshLogin(onDone: () -> Unit) {
        viewModelScope.launch {
            webCookies.clear()
            onDone()
        }
    }

    fun retryVerification() {
        viewModelScope.launch { repository.reverify() }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }
}
