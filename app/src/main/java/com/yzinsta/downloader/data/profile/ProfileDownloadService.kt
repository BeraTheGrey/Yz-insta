package com.yzinsta.downloader.data.profile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yzinsta.downloader.MainActivity
import com.yzinsta.downloader.R
import com.yzinsta.downloader.YzInstaApp
import com.yzinsta.downloader.ui.messageRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a profile download alive while the app is in the background and shows its
 * progress (with a Stop action) as a notification. The work itself is done by
 * [ProfileDownloadManager]; this class only hosts it.
 */
class ProfileDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val manager get() = (application as YzInstaApp).container.profileManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                job?.cancel()
                if (job == null) stopSelf()
            }
            ACTION_START -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: return stopSelf()
        ensureChannel(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification(ProfileJobState.Running(username)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (job?.isActive == true) return // one profile at a time; the UI prevents a second start
        val options = ProfileOptions(
            stories = intent.getBooleanExtra(EXTRA_STORIES, true),
            highlights = intent.getBooleanExtra(EXTRA_HIGHLIGHTS, true),
            posts = intent.getBooleanExtra(EXTRA_POSTS, true),
            reels = intent.getBooleanExtra(EXTRA_REELS, false),
            limit = intent.getIntExtra(EXTRA_LIMIT, 20),
        )
        val updates = scope.launch {
            manager.state.collect { state ->
                if (state is ProfileJobState.Running) notify(NOTIFICATION_ID, progressNotification(state))
            }
        }
        job = scope.launch {
            try {
                manager.run(username, options)
            } finally {
                updates.cancel()
                notify(RESULT_NOTIFICATION_ID, resultNotification(manager.state.value))
                ServiceCompat.stopForeground(this@ProfileDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Android 15+: dataSync services get ~6 h a day; stop cleanly, history lets the user resume. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(id: Int, notification: Notification) {
        val nm = NotificationManagerCompat.from(this)
        if (nm.areNotificationsEnabled()) {
            try {
                nm.notify(id, notification)
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS denied: the job still runs, just without updates.
            }
        }
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(state: ProfileJobState.Running): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProfileDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val c = state.counters
        val text = when {
            state.waitingUntil != null -> getString(
                R.string.profile_waiting,
                ((state.waitingUntil - System.currentTimeMillis()).coerceAtLeast(0) / 60_000 + 1).toInt(),
            )
            else -> getString(R.string.profile_progress_counts, c.downloaded, c.skipped, c.failed)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.profile_notification_title, state.username) +
                (state.at?.let { " · " + sectionLabel(this, it) } ?: ""))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.stop), stop)
        val expected = state.at?.expected
        if (expected != null && expected > 0) {
            builder.setProgress(expected, state.at.position.coerceAtMost(expected), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun resultNotification(state: ProfileJobState): Notification {
        val (title, text) = when (state) {
            is ProfileJobState.Finished -> getString(R.string.profile_done_title, state.username) to
                getString(R.string.profile_progress_counts, state.counters.downloaded, state.counters.skipped, state.counters.failed)
            is ProfileJobState.Cancelled -> getString(R.string.profile_cancelled_title, state.username) to
                getString(R.string.profile_progress_counts, state.counters.downloaded, state.counters.skipped, state.counters.failed)
            is ProfileJobState.Failed -> getString(R.string.profile_failed_title, state.username) to
                getString(state.error.messageRes())
            else -> getString(R.string.app_name) to ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "profile_downloads"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.yzinsta.downloader.PROFILE_START"
        private const val ACTION_STOP = "com.yzinsta.downloader.PROFILE_STOP"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_STORIES = "stories"
        private const val EXTRA_HIGHLIGHTS = "highlights"
        private const val EXTRA_POSTS = "posts"
        private const val EXTRA_REELS = "reels"
        private const val EXTRA_LIMIT = "limit"

        fun start(context: Context, username: String, options: ProfileOptions) {
            val intent = Intent(context, ProfileDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_STORIES, options.stories)
                .putExtra(EXTRA_HIGHLIGHTS, options.highlights)
                .putExtra(EXTRA_POSTS, options.posts)
                .putExtra(EXTRA_REELS, options.reels)
                .putExtra(EXTRA_LIMIT, options.limit)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ProfileDownloadService::class.java).setAction(ACTION_STOP))
        }

        fun sectionLabel(context: Context, at: ProfileStep.Position): String {
            val name = context.getString(
                when (at.section) {
                    "stories" -> R.string.section_stories
                    "highlights" -> R.string.section_highlights
                    "reels" -> R.string.section_reels
                    else -> R.string.section_posts
                }
            )
            return if (at.expected != null) "$name ${at.position}/${at.expected}" else "$name ${at.position}"
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.profile_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
