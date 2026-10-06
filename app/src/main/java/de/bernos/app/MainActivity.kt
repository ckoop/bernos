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
import de.bernos.app.ui.BernosScreen
import de.bernos.app.ui.BernosTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Benachrichtigung ist optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        val controller = sonos
        if (controller.state.value.groups.isEmpty()) controller.discover()

        setContent {
            BernosTheme {
                val state = controller.state.collectAsStateWithLifecycle().value
                // Sobald ein Raum gewählt ist (auch automatisch), Steuerung für Uhr und Sperrbildschirm aktivieren.
                LaunchedEffect(state.selectedGroupId) {
                    if (state.selectedGroupId != null) PlaybackService.start(this@MainActivity)
                }
                BernosScreen(
                    state = state,
                    onRefresh = controller::discover,
                    onAddHost = controller::addHost,
                    onSelectGroup = controller::selectGroup,
                    onPlayPause = controller::togglePlayPause,
                    onNext = controller::next,
                    onPrevious = controller::previous,
                    onVolumeChange = controller::setVolume,
                )
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
