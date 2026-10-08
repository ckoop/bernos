package de.bernos.app.bluetooth

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.bernos.app.MainActivity
import de.bernos.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Benachrichtigt, wenn der Akku der Sonos Ace unter 15 % fällt. Die Benachrichtigung erscheint
 * auch auf der Uhr und verschwindet, sobald die Ace wieder auf 20 % geladen ist.
 */
class LowBatteryNotifier(context: Context) {
    private val context = context.applicationContext
    private val alert = LowBatteryAlert()

    fun start(battery: StateFlow<HeadphoneBattery?>, scope: CoroutineScope) {
        createChannel()
        scope.launch {
            battery.collect { value ->
                when (val action = alert.update(value?.level)) {
                    is LowBatteryAlert.Action.Warn -> notify(value?.name ?: "Sonos Ace", action.level)
                    LowBatteryAlert.Action.Clear -> NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                    LowBatteryAlert.Action.None -> Unit
                }
            }
        }
    }

    private fun notify(name: String, level: Int) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(context.getString(R.string.low_battery_title, name))
            .setContentText(context.getString(R.string.low_battery_text, level))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.low_battery_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.low_battery_channel_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "headphone_battery"
        // 1 ist die Wiedergabe-Benachrichtigung des PlaybackService.
        const val NOTIFICATION_ID = 2
    }
}
