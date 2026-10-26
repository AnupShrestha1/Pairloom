package com.anupshrestha.pairloom

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** What the ongoing notification shows; null when there is no link. */
sealed interface LinkNotice {
    val peer: String

    data class Linked(override val peer: String) : LinkNotice
    data class InCall(override val peer: String, val muted: Boolean) : LinkNotice
    data class PushToTalk(override val peer: String) : LinkNotice

    /** The other phone calls (or asks for push-to-talk) and this phone's user has to accept. */
    data class Incoming(override val peer: String, val pushToTalk: Boolean) : LinkNotice

    /** The link dropped and the phones are trying to get it back. */
    data class Reconnecting(override val peer: String) : LinkNotice

    /** Whether the microphone is in use (the foreground service needs the microphone type then). */
    val usesMicrophone: Boolean get() = this is InCall || this is PushToTalk
}

/**
 * Keeps the process in the foreground while a link is up, so that a call or push-to-talk goes on
 * with the screen off or another app open, and shows the ongoing notification with its actions.
 *
 * Type "connected device" while linked, plus "microphone" while the microphone is in use. Calls
 * are only started or accepted inside the app, so the microphone type is always added from the
 * foreground, as Android 14+ requires. The link itself lives in [PairloomApp]; this service only
 * follows it ([sync]) and forwards the notification's buttons.
 */
class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val link = (application as PairloomApp).link
        when (intent?.action) {
            ACTION_MUTE -> link.toggleMute()
            ACTION_END -> link.stopMedia()
            ACTION_DISCONNECT -> link.disconnect()
        }
        val notice = current
        if (notice == null) {
            stopSelfSafely()
        } else {
            show(notice)
        }
        // The link is in memory only: if the process dies, there is nothing to restart.
        return START_NOT_STICKY
    }

    private var shownTypes = -1

    fun show(notice: LinkNotice) {
        val types = if (Build.VERSION.SDK_INT >= 29) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (notice.usesMicrophone && micGranted()) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            t
        } else {
            0
        }
        val notification = build(this, notice)
        try {
            if (types != shownTypes) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
                shownTypes = types
            } else {
                notificationManager(this).notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // For example the microphone type from the background (Android 14+). The call itself
            // does not depend on it while the app is visible.
            Log.w(TAG, "startForeground failed: ${e.message}")
            runCatching { notificationManager(this).notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun micGranted() = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun stopSelfSafely() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    companion object {
        private const val TAG = "Pairloom"
        private const val NOTIFICATION_ID = 1
        const val CHANNEL_ONGOING = "link"
        const val CHANNEL_INCOMING = "incoming"
        private const val ACTION_MUTE = "com.anupshrestha.pairloom.MUTE"
        private const val ACTION_END = "com.anupshrestha.pairloom.END"
        private const val ACTION_DISCONNECT = "com.anupshrestha.pairloom.DISCONNECT"

        @Volatile private var instance: LinkService? = null
        @Volatile private var current: LinkNotice? = null

        /**
         * Follows the link (main thread). Starts the service when a link comes up (always from the
         * app, which is then in the foreground), updates it while it runs, stops it when the link
         * ends.
         */
        fun sync(context: Context, notice: LinkNotice?) {
            current = notice
            val running = instance
            when {
                notice == null -> running?.stopSelfSafely()
                running != null -> running.show(notice)
                else -> try {
                    ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
                } catch (e: Exception) {
                    // Not allowed from the background on Android 12+. The link still works while
                    // the app is visible; show the notification at least.
                    Log.w(TAG, "Could not start the link service: ${e.message}")
                    runCatching { notificationManager(context).notify(NOTIFICATION_ID, build(context, notice)) }
                }
            }
        }

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = notificationManager(context)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_ongoing_description)
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_INCOMING, context.getString(R.string.channel_incoming), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_incoming_description)
                },
            )
        }

        private fun notificationManager(context: Context) =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        private fun action(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                context, code, Intent(context, LinkService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun build(context: Context, notice: LinkNotice): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val incoming = notice is LinkNotice.Incoming
            val b = NotificationCompat.Builder(context, if (incoming) CHANNEL_INCOMING else CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(!incoming)
                .setContentIntent(open)
                .setCategory(if (incoming || notice.usesMicrophone) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
                .setPriority(if (incoming) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            when (notice) {
                is LinkNotice.Linked -> b.setContentTitle(context.getString(R.string.notice_linked, notice.peer))
                    .setContentText(context.getString(R.string.notice_linked_text))
                    .addAction(0, context.getString(R.string.btn_disconnect), action(context, ACTION_DISCONNECT, 3))
                is LinkNotice.InCall -> b.setContentTitle(context.getString(R.string.call_active, notice.peer))
                    .setContentText(context.getString(if (notice.muted) R.string.notice_muted else R.string.notice_call_text))
                    .setUsesChronometer(true)
                    .addAction(0, context.getString(if (notice.muted) R.string.btn_unmute else R.string.btn_mute), action(context, ACTION_MUTE, 1))
                    .addAction(0, context.getString(R.string.btn_end_call), action(context, ACTION_END, 2))
                is LinkNotice.PushToTalk -> b.setContentTitle(context.getString(R.string.ptt_active, notice.peer))
                    .setContentText(context.getString(R.string.notice_ptt_text))
                    .addAction(0, context.getString(R.string.btn_end_ptt), action(context, ACTION_END, 2))
                is LinkNotice.Incoming -> b.setContentTitle(
                    context.getString(if (notice.pushToTalk) R.string.ptt_incoming else R.string.call_incoming, notice.peer),
                ).setContentText(context.getString(R.string.notice_incoming_text))
                is LinkNotice.Reconnecting -> b.setContentTitle(context.getString(R.string.notice_reconnecting, notice.peer))
                    .setContentText(context.getString(R.string.notice_reconnecting_text))
                    .addAction(0, context.getString(R.string.btn_cancel), action(context, ACTION_DISCONNECT, 3))
            }
            return b.build()
        }
    }
}
