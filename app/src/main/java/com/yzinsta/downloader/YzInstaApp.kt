package com.yzinsta.downloader

import android.app.Application
import com.yzinsta.downloader.data.download.DownloadRepository
import com.yzinsta.downloader.data.download.MediaStoreExporter
import com.yzinsta.downloader.data.history.DownloadHistory
import com.yzinsta.downloader.data.history.SqliteDownloadHistory
import com.yzinsta.downloader.data.profile.ProfileDownloadManager
import com.yzinsta.downloader.data.python.ChaquopyBridge
import com.yzinsta.downloader.data.python.InstaloaderBridge
import com.yzinsta.downloader.data.session.AndroidWebCookieJar
import com.yzinsta.downloader.data.session.EncryptedSessionStore
import com.yzinsta.downloader.data.session.SessionRepository
import com.yzinsta.downloader.data.session.WebCookieJar

class YzInstaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency container; small enough that a DI framework isn't worth it yet. */
class AppContainer(app: Application) {
    val bridge: InstaloaderBridge = ChaquopyBridge(app)
    val webCookies: WebCookieJar = AndroidWebCookieJar()
    val sessionRepository = SessionRepository(bridge, EncryptedSessionStore(app), webCookies)
    private val exporter = MediaStoreExporter(app)
    val history: DownloadHistory = SqliteDownloadHistory(app)
    val downloadRepository = DownloadRepository(bridge, exporter, app.cacheDir, history)
    val profileManager = ProfileDownloadManager(
        bridge, exporter, history, sessionRepository, java.io.File(app.cacheDir, "profile"),
    )
}
