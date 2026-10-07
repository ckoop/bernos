package de.bernos.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.bernos.wear.ui.BernosWear
import de.bernos.wear.ui.WearActions
import de.bernos.wearprotocol.WatchCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phone = phone
        val actions = object : WearActions {
            override fun selectGroup(groupId: String) = phone.send(WatchCommand.SelectGroup(groupId))
            override fun playPause() = phone.send(WatchCommand.PlayPause)
            override fun next() = phone.send(WatchCommand.Next)
            override fun previous() = phone.send(WatchCommand.Previous)
            override fun setVolume(volume: Int) = phone.send(WatchCommand.SetVolume(volume))
            override fun refresh() = phone.send(WatchCommand.Refresh)
            override fun reconnect() = phone.send(WatchCommand.Hello)
        }

        setContent {
            BernosWear(
                state = phone.state.collectAsStateWithLifecycle().value,
                cover = phone.cover.collectAsStateWithLifecycle().value,
                connection = phone.connection.collectAsStateWithLifecycle().value,
                actions = actions,
            )
        }

        // Solange die App sichtbar ist, das Handy regelmäßig anstoßen: Es aktualisiert dann
        // den Zustand, auch wenn Android die Handy-App im Hintergrund schlafen legt.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    phone.send(WatchCommand.Hello)
                    delay(HEARTBEAT_MS)
                }
            }
        }
    }

    private companion object {
        const val HEARTBEAT_MS = 10_000L
    }
}
