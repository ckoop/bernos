package de.bernos.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import de.bernos.wearprotocol.WearProtocol

/**
 * Empfängt neue Zustände vom Handy auch dann, wenn die Uhr-App geschlossen ist, damit
 * Kachel und Komplikation aktuell bleiben. Android startet dafür bei Bedarf den Prozess.
 */
class StateListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearProtocol.STATE_PATH }
            .map { it.dataItem.freeze() }
            .forEach { phone.onDataItem(it) }
    }
}
