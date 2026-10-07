package de.bernos.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * Bernos-Symbol auf dem Zifferblatt, solange Musik läuft ("Ongoing Activity"). Ein Tipp öffnet
 * die Uhr-App. Ersetzt die System-Mediensteuerung, die früher die Handy-Benachrichtigung übernahm.
 */
object NowPlayingIndicator {
    private const val CHANNEL_ID = "playing"
    private const val NOTIFICATION_ID = 1

    fun update(context: Context, summary: NowPlayingSummary) {
        if (summary.isPlaying && summary.room != null) show(context, summary) else hide(context)
    }

    private fun show(context: Context, summary: NowPlayingSummary) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = summary.longText ?: summary.room.orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bernos)
            .setContentTitle(summary.room)
            .setContentText(text)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        OngoingActivity.Builder(context, NOTIFICATION_ID, notification)
            .setStaticIcon(R.drawable.ic_bernos)
            .setTouchIntent(openApp)
            .setTitle(context.getString(R.string.app_name))
            .setStatus(Status.forPart(Status.TextPart(text)))
            .build()
            .apply(context)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification.build())
    }

    private fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.playing_channel), NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
