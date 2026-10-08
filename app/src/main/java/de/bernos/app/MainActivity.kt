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

    // Beides ist optional: Benachrichtigung für die Steuerung, Bluetooth für den Akku der Sonos Ace.
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { (application as BernosApp).headphones.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestOptionalPermissions()

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
            override fun setSleepTimer(minutes: Int?) = controller.setSleepTimer(minutes)
            override fun switchToTv() = controller.switchToTv()
            override fun setNightMode(enabled: Boolean) = controller.setNightMode(enabled)
            override fun setSpeechEnhancement(enabled: Boolean) = controller.setSpeechEnhancement(enabled)
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
                val headphones = app.headphones.battery.collectAsStateWithLifecycle().value
                BernosScreen(state, actions, headphones)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val controller = sonos
        controller.resumeTracking()
        // Raumliste: was in allen Räumen läuft und Akkustand, solange die App sichtbar ist.
        controller.startOverview()
        (application as BernosApp).headphones.refresh()
        if (controller.state.value.selectedGroupId != null) PlaybackService.start(this)
    }

    override fun onStop() {
        sonos.stopOverview()
        super.onStop()
    }

    private fun requestOptionalPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionRequest.launch(wanted.toTypedArray())
    }
}
