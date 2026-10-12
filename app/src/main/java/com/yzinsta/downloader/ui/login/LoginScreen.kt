package com.yzinsta.downloader.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.yzinsta.downloader.R
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

/** Hosts we let the login flow navigate to (Instagram itself and "Log in with Facebook"). */
private fun isAllowedHost(uri: Uri): Boolean {
    val host = uri.host ?: return false
    return uri.scheme == "https" && listOf("instagram.com", "facebook.com", "fbcdn.net")
        .any { host == it || host.endsWith(".$it") }
}

/**
 * Instagram's own web login in a WebView. The app never sees the password: we
 * only read cookies from CookieManager after each page load, and no JavaScript
 * is injected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onPageFinished: suspend () -> Boolean,
    onCancel: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onCancel()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.login_title)) },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                }
            },
        )
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true // required by instagram.com
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            private var done = false
                            private var checking = false

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !isAllowedHost(request.url)

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                if (done || checking) return
                                checking = true
                                CookieManager.getInstance().flush()
                                scope.launch {
                                    done = onPageFinished()
                                    checking = false
                                }
                            }
                        }
                        loadUrl(LOGIN_URL)
                        webView = this
                    }
                },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}
