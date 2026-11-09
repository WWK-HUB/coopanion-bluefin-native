package com.wwkhub.bluefin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Ongoing notification that returns the user to the in-app preview and
 * shows or hides the system overlay. The overlay window itself is a
 * separate TYPE_APPLICATION_OVERLAY, not this foreground service.
 */
class BluefinForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_OVERLAY -> OverlayLauncher.onNotificationToggle(this)
            null -> OverlayLauncher.restoreIfEnabled(this)
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        createChannel()
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val showing = OverlayLauncher.isShowing()
        val label = if (showing) R.string.notif_overlay_hide else R.string.notif_overlay_show
        val overlayAction = overlayAction(showing)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bluefin)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_return), open)
            .addAction(0, getString(label), overlayAction)
            .build()
    }

    private fun overlayAction(showing: Boolean): PendingIntent {
        val needsGuide = !showing && !Settings.canDrawOverlays(this)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (needsGuide) {
            PendingIntent.getActivity(
                this,
                REQUEST_OVERLAY,
                Intent(this, MainActivity::class.java).apply {
                    action = OverlayLauncher.ACTION_OVERLAY_GUIDE
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                flags,
            )
        } else {
            PendingIntent.getService(
                this,
                REQUEST_OVERLAY,
                Intent(this, BluefinForegroundService::class.java).apply {
                    action = ACTION_TOGGLE_OVERLAY
                },
                flags,
            )
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "bluefin_presence"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 1
        private const val REQUEST_OVERLAY = 2
        private const val ACTION_TOGGLE_OVERLAY = "com.wwkhub.bluefin.action.TOGGLE_OVERLAY"
        private const val ACTION_REFRESH = "com.wwkhub.bluefin.action.REFRESH_NOTIFICATION"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BluefinForegroundService::class.java),
            )
        }

        fun refresh(context: Context) {
            if (!canPostNotifications(context)) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, BluefinForegroundService::class.java).apply {
                    action = ACTION_REFRESH
                },
            )
        }

        private fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 33) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
