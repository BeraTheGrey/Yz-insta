package com.yzinsta.downloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yzinsta.downloader.data.session.SessionState
import com.yzinsta.downloader.ui.download.DownloadSection
import com.yzinsta.downloader.ui.download.DownloadViewModel
import com.yzinsta.downloader.ui.home.HomeScreen
import com.yzinsta.downloader.ui.home.WelcomeScreen
import com.yzinsta.downloader.ui.login.LoginScreen
import com.yzinsta.downloader.ui.session.SessionViewModel
import com.yzinsta.downloader.ui.theme.YzInstaTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as YzInstaApp).container

    private val sessionVm: SessionViewModel by viewModels {
        viewModelFactory { initializer { SessionViewModel(container.sessionRepository, container.webCookies) } }
    }
    private val downloadVm: DownloadViewModel by viewModels {
        viewModelFactory {
            initializer { DownloadViewModel(container.downloadRepository, container.sessionRepository, container.profileManager) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            YzInstaTheme {
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AppRoot(sessionVm, downloadVm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Links shared from Instagram (or anywhere else) via the share sheet. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(downloadVm::onSharedText)
    }
}

@Composable
private fun AppRoot(sessionVm: SessionViewModel, downloadVm: DownloadViewModel) {
    val session by sessionVm.state.collectAsStateWithLifecycle()
    var showLogin by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(session) {
        if (session !is SessionState.LoggedOut) showLogin = false
    }

    when (val s = session) {
        SessionState.Checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is SessionState.LoggedOut -> if (showLogin) {
            LoginScreen(
                onPageFinished = { sessionVm.tryCompleteWebLogin() },
                onCancel = { showLogin = false },
            )
        } else {
            WelcomeScreen(
                reason = s.reason,
                onLogin = { sessionVm.clearWebCookiesForFreshLogin { showLogin = true } },
                onCookieLogin = sessionVm::loginWithPastedCookies,
            )
        }
        is SessionState.LoggedIn -> HomeScreen(
            session = s,
            onRetryVerification = sessionVm::retryVerification,
            onLogout = sessionVm::logout,
        ) {
            // A shared link waits in the ViewModel until we are logged in; autoStart fires here.
            val download by downloadVm.state.collectAsStateWithLifecycle()
            DownloadSection(download, downloadVm)
        }
    }
}
