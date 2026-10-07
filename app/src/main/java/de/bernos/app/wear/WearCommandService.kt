package de.bernos.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.bernos.app.BernosApp
import de.bernos.app.PlaybackService
import de.bernos.wearprotocol.WatchCommand
import de.bernos.wearprotocol.WearProtocol

/**
 * Nimmt Befehle der Uhr entgegen. Android startet dafür bei Bedarf den App-Prozess,
 * auch wenn die Handy-App gerade nicht geöffnet ist.
 */
class WearCommandService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.COMMAND_PATH) return
        val command = WatchCommand.decode(event.data) ?: return
        Log.d("WearCommandService", "Befehl von der Uhr: $command")
        val app = application as BernosApp
        val controller = app.controller
        when (command) {
            WatchCommand.Hello, WatchCommand.Refresh -> {
                if (controller.state.value.groups.isEmpty() || command == WatchCommand.Refresh) app.discover()
                controller.resumeTracking()
                controller.refresh()
                startPlaybackService()
            }
            is WatchCommand.SelectGroup -> {
                controller.selectGroup(command.groupId)
                startPlaybackService()
            }
            WatchCommand.PlayPause -> controller.togglePlayPause()
            WatchCommand.Next -> controller.next()
            WatchCommand.Previous -> controller.previous()
            is WatchCommand.SetVolume -> controller.setVolume(command.volume)
            is WatchCommand.MoveTo -> controller.movePlaybackTo(command.roomUuid)
        }
    }

    /**
     * Hält die Verfolgung am Laufen, solange ein Raum gewählt ist. Aus dem Hintergrund darf
     * Android den Dienst verweigern; dann hält die Uhr das Handy mit regelmäßigen Nachrichten wach.
     */
    private fun startPlaybackService() {
        if ((application as BernosApp).controller.state.value.selectedGroupId == null) return
        runCatching { PlaybackService.start(this) }
    }
}
