package de.bernos.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import de.bernos.wearprotocol.WatchCommand
import de.bernos.wearprotocol.WatchState
import de.bernos.wearprotocol.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

/** Cover als unkomprimiertes RGB_565-Bild für die Kachel; [version] ändert sich mit dem Bild. */
class TileImage(val data: ByteArray, val widthPx: Int, val heightPx: Int, val version: String)

/** Wie gut die Uhr die Handy-App erreicht. */
enum class PhoneConnection { CONNECTING, CONNECTED, UNREACHABLE }

/**
 * Verbindung zur Handy-App über die Wearable Data Layer API: empfängt den Zustand samt
 * Cover als DataItem und schickt Befehle als Nachricht an das Handy.
 */
class PhoneLink(context: Context, private val scope: CoroutineScope) {
    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val capabilityClient = Wearable.getCapabilityClient(context)

    private val _state = MutableStateFlow<WatchState?>(null)
    val state: StateFlow<WatchState?> = _state.asStateFlow()

    private val _cover = MutableStateFlow<ImageBitmap?>(null)
    val cover: StateFlow<ImageBitmap?> = _cover.asStateFlow()

    private val _tileCover = MutableStateFlow<TileImage?>(null)
    val tileCover: StateFlow<TileImage?> = _tileCover.asStateFlow()

    private val _connection = MutableStateFlow(PhoneConnection.CONNECTING)
    val connection: StateFlow<PhoneConnection> = _connection.asStateFlow()

    private var coverUrl: String? = null

    /** Gewünschte Lautstärke; beim schnellen Drehen der Lünette wird nur der letzte Wert geschickt. */
    private val pendingVolume = MutableStateFlow<Int?>(null)

    private val listener = DataClient.OnDataChangedListener { events ->
        Log.d(TAG, "Data-Layer-Ereignisse: ${events.count}")
        // Der Puffer wird nach dem Aufruf freigegeben, daher die Daten sofort herauslösen.
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearProtocol.STATE_PATH }
            .map { it.dataItem.freeze() }
            .forEach { onDataItem(it) }
    }

    fun start() {
        dataClient.addListener(listener)
        scope.launch { loadStored() }
        scope.launch {
            pendingVolume.filterNotNull().collect { volume ->
                transmit(WatchCommand.SetVolume(volume))
                delay(VOLUME_THROTTLE_MS)
            }
        }
    }

    /** Aktueller Zustand; lädt beim Kaltstart (z. B. für Kachel oder Komplikation) den gespeicherten. */
    suspend fun awaitState(): WatchState? {
        if (_state.value == null) loadStored()
        return _state.value
    }

    /** Zuletzt bekannten Zustand laden, falls sich seit dem letzten Start nichts geändert hat. */
    private suspend fun loadStored() {
        runCatching {
            val items = dataClient.dataItems.await()
            try {
                items.firstOrNull { it.uri.path == WearProtocol.STATE_PATH }?.freeze()
            } finally {
                items.release()
            }
        }.getOrNull()?.let { onDataItem(it) }
    }

    fun send(command: WatchCommand) {
        // Sofort anzeigen, das Handy bestätigt kurz darauf.
        when (command) {
            WatchCommand.PlayPause -> _state.update { it?.copy(isPlaying = !it.isPlaying) }
            is WatchCommand.SetMuted -> _state.update { it?.copy(muted = command.muted) }
            is WatchCommand.SetNightMode -> _state.update { it?.copy(homeTheater = it.homeTheater?.copy(nightMode = command.enabled)) }
            is WatchCommand.SetSpeechEnhancement ->
                _state.update { it?.copy(homeTheater = it.homeTheater?.copy(speechEnhancement = command.enabled)) }
            is WatchCommand.SetVolume -> {
                _state.update { it?.copy(volume = command.volume) }
                pendingVolume.value = command.volume
                return
            }
            is WatchCommand.SelectGroup -> _state.update {
                it?.copy(selectedGroupId = command.groupId, title = null, artist = null, album = null, coverUrl = null, volume = null)
            }
            else -> Unit
        }
        scope.launch { transmit(command) }
    }

    private suspend fun transmit(command: WatchCommand) {
        try {
            val nodes = capabilityClient
                .getCapability(WearProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (node == null) {
                _connection.value = PhoneConnection.UNREACHABLE
                return
            }
            messageClient.sendMessage(node.id, WearProtocol.COMMAND_PATH, command.encode()).await()
            Log.d(TAG, "Befehl gesendet: $command an ${node.displayName}")
            _connection.value = PhoneConnection.CONNECTED
        } catch (e: Exception) {
            Log.w(TAG, "Befehl nicht gesendet: ${e.message}")
            _connection.value = PhoneConnection.UNREACHABLE
        }
    }

    fun onDataItem(item: DataItem) {
        val dataMap = DataMapItem.fromDataItem(item).dataMap
        val state = dataMap.getByteArray(WearProtocol.STATE_KEY)?.let { WatchState.decode(it) } ?: return
        Log.d(TAG, "Zustand empfangen: Raum=${state.selectedGroupId}, Titel=${state.title}, ${item.uri}")
        _state.value = state
        _connection.value = PhoneConnection.CONNECTED
        if (state.coverUrl != coverUrl) {
            coverUrl = state.coverUrl
            val asset = dataMap.getAsset(WearProtocol.COVER_ASSET)
            if (asset == null || state.coverUrl == null) {
                _cover.value = null
                _tileCover.value = null
            } else {
                scope.launch { loadCover(asset, state.coverUrl) }
            }
        }
    }

    private suspend fun loadCover(asset: Asset, url: String?) {
        val bitmap = runCatching {
            dataClient.getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (url != coverUrl) return
        _cover.value = bitmap?.asImageBitmap()
        _tileCover.value = bitmap?.let { toTileImage(it, url) }
    }

    /** Kacheln zeigen eingebettete Bilder am sichersten unkomprimiert; klein halten. */
    private fun toTileImage(source: Bitmap, url: String?): TileImage {
        val scaled = source.scale(TILE_COVER_PX, TILE_COVER_PX)
        val rgb565 = scaled.copy(Bitmap.Config.RGB_565, false)
        val buffer = ByteBuffer.allocate(rgb565.byteCount)
        rgb565.copyPixelsToBuffer(buffer)
        return TileImage(buffer.array(), rgb565.width, rgb565.height, version = "cover-${url.hashCode()}")
    }

    private companion object {
        const val TAG = "PhoneLink"
        const val VOLUME_THROTTLE_MS = 120L
        const val TILE_COVER_PX = 160
    }
}
