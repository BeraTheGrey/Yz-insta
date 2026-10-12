package com.yzinsta.downloader

import android.app.Application
import com.yzinsta.downloader.data.python.ChaquopyBridge
import com.yzinsta.downloader.data.python.InstaloaderBridge

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
}
