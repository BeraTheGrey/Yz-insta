package com.yzinsta.downloader.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.yzinsta.downloader.R
import com.yzinsta.downloader.ui.home.ErrorCard
import com.yzinsta.downloader.domain.AppError
import kotlinx.coroutines.launch

private const val TAG = "LoginWebView"
private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

/** Desktop Chrome UA for the "desktop site" fallback when the mobile site misbehaves in a WebView. */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

/**
 * Instagram's own web login in a WebView (same approach as Seal's cookie page). The app
 * never sees the password: we only read cookies from CookieManager after page loads, and
 * no JavaScript is injected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onPageFinished: suspend () -> Boolean,
    onCancel: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun applyUserAgent(view: WebView, desktop: Boolean) {
        // null restores the WebView default.
        view.settings.userAgentString = if (desktop) DESKTOP_UA else null
    }

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
            actions = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reload)) },
                        onClick = {
                            menuOpen = false
                            loadError = null
                            webView?.reload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.desktop_site)) },
                        trailingIcon = { Checkbox(checked = desktopMode, onCheckedChange = null) },
                        onClick = {
                            menuOpen = false
                            desktopMode = !desktopMode
                            loadError = null
                            webView?.let {
                                applyUserAgent(it, desktopMode)
                                it.loadUrl(LOGIN_URL)
                            }
                        },
                    )
                }
            },
        )
        loadError?.let {
            ErrorCard(
                AppError.Unknown(it),
                modifier = Modifier.padding(8.dp),
                actionLabel = stringResource(R.string.reload),
                onAction = {
                    loadError = null
                    // After a renderer crash there is no WebView left to reload; leave and re-enter.
                    webView?.reload() ?: onCancel()
                },
            )
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true // required by instagram.com
                        settings.javaScriptCanOpenWindowsAutomatically = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        applyUserAgent(this, desktopMode)
                        // Don't announce the app's package name to Instagram (embedded-browser detection).
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                        }
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.d(TAG, "console ${message.messageLevel()}: ${message.message()} " +
                                    "(${message.sourceId()}:${message.lineNumber()})")
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            private var done = false
                            private var checking = false

                            // Like Seal: any http(s) page may load, including iframes and
                            // redirects through other Meta domains; app links such as
                            // intent:// or instagram:// are swallowed so we stay in the WebView.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val scheme = request.url.scheme?.lowercase()
                                return scheme != "http" && scheme != "https"
                            }

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

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                Log.w(TAG, "load error ${error.errorCode} ${error.description} for ${request.url}")
                                if (request.isForMainFrame) loadError = "${error.description} (${error.errorCode})"
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse,
                            ) {
                                Log.w(TAG, "HTTP ${errorResponse.statusCode} for ${request.url}")
                                if (request.isForMainFrame) loadError = "HTTP ${errorResponse.statusCode}"
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                // Without this the whole app would crash with the WebView renderer.
                                Log.e(TAG, "renderer gone, crashed=${detail.didCrash()}")
                                loadError = "WebView renderer stopped"
                                webView = null
                                view.destroy()
                                return true
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
