package de.bernos.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.bernos.sonos.NowPlaying
import de.bernos.sonos.SonosState
import de.bernos.sonos.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Meldet die Sonos-Wiedergabe beim System als Mediensitzung an.
 *
 * Dadurch erscheinen Titel, Cover und Steuerknöpfe in der Benachrichtigung, auf dem
 * Sperrbildschirm und in der Mediensteuerung der Wear-OS-Uhr. Die Lautstärketasten
 * des Handys regeln die Sonos-Gruppe.
 *
 * Wear OS übernimmt die Mediensitzung auch ohne weitergereichte Benachrichtigung; das Symbol
 * auf dem Zifferblatt öffnet daher immer den System-Player (bis Wear OS 6 nicht abschaltbar).
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private lateinit var volumeProvider: VolumeProviderCompat

    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        volumeProvider = object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, 0) {
            override fun onSetVolumeTo(volume: Int) {
                currentVolume = volume
                sonos.setVolume(volume)
            }

            override fun onAdjustVolume(direction: Int) {
                if (direction == 0) return
                val volume = (currentVolume + direction * VOLUME_STEP).coerceIn(0, 100)
                currentVolume = volume
                sonos.setVolume(volume)
            }
        }

        session = MediaSessionCompat(this, "Bernos").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = sonos.play()
                override fun onPause() = sonos.pause()
                override fun onSkipToNext() = sonos.next()
                override fun onSkipToPrevious() = sonos.previous()
                override fun onStop() = shutdown()
            })
            setSessionActivity(openAppIntent())
            setPlaybackToRemote(volumeProvider)
            isActive = true
        }

        scope.launch {
            sonos.state.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android verlangt, dass ein Vordergrunddienst sofort seine Benachrichtigung zeigt.
        if (!inForeground) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(sonos.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            inForeground = true
        }
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> sonos.togglePlayPause()
            ACTION_NEXT -> sonos.next()
            ACTION_PREVIOUS -> sonos.previous()
            ACTION_STOP -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun shutdown() {
        // Ohne Benachrichtigung soll im Hintergrund nichts mehr abgefragt werden.
        sonos.stopTracking()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    private fun render(state: SonosState) {
        if (state.selectedGroupId == null) {
            if (inForeground) shutdown()
            return
        }
        val nowPlaying = state.nowPlaying
        loadArtwork(nowPlaying?.track?.albumArtUrl)

        session.setMetadata(buildMetadata(state))
        session.setPlaybackState(buildPlaybackState(nowPlaying))
        nowPlaying?.volume?.let { if (it != volumeProvider.currentVolume) volumeProvider.currentVolume = it }

        if (inForeground) {
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (allowed) NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildMetadata(state: SonosState): MediaMetadataCompat {
        val track = state.nowPlaying?.track
        val title = track?.title ?: state.selectedGroup?.name ?: getString(R.string.app_name)
        return MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track?.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track?.album)
            .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, track?.albumArtUrl)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, state.selectedGroup?.name)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.nowPlaying?.durationMs ?: -1L)
            .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
            .build()
    }

    private fun buildPlaybackState(nowPlaying: NowPlaying?): PlaybackStateCompat {
        val state = when (nowPlaying?.transportState) {
            TransportState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            TransportState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            TransportState.TRANSITIONING -> PlaybackStateCompat.STATE_BUFFERING
            TransportState.STOPPED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val position = nowPlaying?.positionMs ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        val speed = if (nowPlaying?.isPlaying == true) 1f else 0f
        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP,
            )
            // Die Uhrzeit der Messung angeben, damit das System die Position selbst weiterzählt.
            .setState(state, position, speed, nowPlaying?.positionCapturedAtElapsed() ?: android.os.SystemClock.elapsedRealtime())
            .build()
    }

    /** [NowPlaying.positionCapturedAtMs] ist Wanduhrzeit; MediaSession erwartet `elapsedRealtime`. */
    private fun NowPlaying.positionCapturedAtElapsed(): Long =
        android.os.SystemClock.elapsedRealtime() - (System.currentTimeMillis() - positionCapturedAtMs).coerceAtLeast(0)

    private fun buildNotification(state: SonosState): android.app.Notification {
        val nowPlaying = state.nowPlaying
        val playing = nowPlaying?.isPlaying == true
        val track = nowPlaying?.track
        val title = track?.title ?: state.selectedGroup?.name ?: getString(R.string.app_name)
        val text = listOfNotNull(track?.artist, state.selectedGroup?.name.takeIf { track?.title != null })
            .joinToString(" · ")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { getString(R.string.nothing_playing) })
            .setLargeIcon(artwork)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .addAction(R.drawable.ic_skip_previous, getString(R.string.previous), serviceIntent(ACTION_PREVIOUS))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                serviceIntent(ACTION_PLAY_PAUSE),
            )
            .addAction(R.drawable.ic_skip_next, getString(R.string.next), serviceIntent(ACTION_NEXT))
            .addAction(R.drawable.ic_close, getString(R.string.stop_control), serviceIntent(ACTION_STOP))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun loadArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkUrl = url
        artwork = null
        artworkJob?.cancel()
        if (url == null) return
        artworkJob = scope.launch {
            val request = ImageRequest.Builder(this@PlaybackService)
                .data(url)
                .size(ARTWORK_SIZE_PX)
                .allowHardware(false) // MediaSession und Benachrichtigung brauchen eine Software-Bitmap.
                .build()
            val result = SingletonImageLoader.get(this@PlaybackService).execute(request)
            if (url == artworkUrl && result is SuccessResult) {
                artwork = result.image.toBitmap()
                render(sonos.state.value)
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val VOLUME_STEP = 2
        private const val ARTWORK_SIZE_PX = 512

        private const val ACTION_PLAY_PAUSE = "de.bernos.app.PLAY_PAUSE"
        private const val ACTION_NEXT = "de.bernos.app.NEXT"
        private const val ACTION_PREVIOUS = "de.bernos.app.PREVIOUS"
        private const val ACTION_STOP = "de.bernos.app.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }
}
