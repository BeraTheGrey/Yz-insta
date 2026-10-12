package com.yzinsta.downloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yzinsta.downloader.data.session.SessionState
import com.yzinsta.downloader.ui.home.HomeScreen
import com.yzinsta.downloader.ui.home.WelcomeScreen
import com.yzinsta.downloader.ui.login.LoginScreen
import com.yzinsta.downloader.ui.session.SessionViewModel
import com.yzinsta.downloader.ui.theme.YzInstaTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as YzInstaApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YzInstaTheme {
                val sessionVm: SessionViewModel = viewModel(factory = viewModelFactory {
                    initializer { SessionViewModel(container.sessionRepository, container.webCookies) }
                })
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AppRoot(sessionVm)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot(sessionVm: SessionViewModel) {
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
            )
        }
        is SessionState.LoggedIn -> HomeScreen(
            session = s,
            onRetryVerification = sessionVm::retryVerification,
            onLogout = sessionVm::logout,
        ) {
            Text(stringResource(R.string.download_coming_soon))
        }
    }
}
