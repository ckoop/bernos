package de.bernos.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bernos.app.ui.BernosActions
import de.bernos.app.ui.BernosScreen
import de.bernos.app.ui.BernosTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Benachrichtigung ist optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        val app = application as BernosApp
        val controller = app.controller
        if (controller.state.value.groups.isEmpty()) app.discover()
        val actions = object : BernosActions {
            override fun refresh() = app.discover()
            override fun addHost(host: String) = controller.addHost(host)
            override fun selectGroup(groupId: String?) = controller.selectGroup(groupId)
            override fun playPause() = controller.togglePlayPause()
            override fun next() = controller.next()
            override fun previous() = controller.previous()
            override fun setVolume(volume: Int) = controller.setVolume(volume)
            override fun toggleMute() = controller.toggleMuted()
            override fun setRoomVolume(roomUuid: String, volume: Int) = controller.setRoomVolume(roomUuid, volume)
            override fun addRoom(roomUuid: String) = controller.addRoomToGroup(roomUuid)
            override fun removeRoom(roomUuid: String) = controller.removeRoomFromGroup(roomUuid)
            override fun moveTo(roomUuid: String) = controller.movePlaybackTo(roomUuid)
            override fun loadFavorites() = controller.loadFavorites()
            override fun playFavorite(favoriteId: String) = controller.playFavorite(favoriteId)
        }

        setContent {
            BernosTheme {
                val state = controller.state.collectAsStateWithLifecycle().value
                // Sobald ein Raum gewählt ist (auch automatisch), Steuerung für Uhr und Sperrbildschirm aktivieren.
                LaunchedEffect(state.selectedGroupId) {
                    if (state.selectedGroupId != null) PlaybackService.start(this@MainActivity)
                }
                BernosScreen(state, actions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val controller = sonos
        controller.resumeTracking()
        if (controller.state.value.selectedGroupId != null) PlaybackService.start(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
