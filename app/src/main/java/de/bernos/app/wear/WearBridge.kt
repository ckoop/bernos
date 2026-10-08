package de.bernos.app.wear

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.bernos.app.bluetooth.HeadphoneBattery
import de.bernos.sonos.SonosController
import de.bernos.wearprotocol.WatchState
import de.bernos.wearprotocol.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/**
 * Veröffentlicht den Zustand für die Uhr als DataItem. Die Data Layer überträgt ihn, sobald
 * die Uhr verbunden ist, und meldet nur echte Änderungen weiter.
 */
class WearBridge(
    private val context: Context,
    private val controller: SonosController,
    private val headphones: StateFlow<HeadphoneBattery?>,
    private val scope: CoroutineScope,
) {
    private val dataClient by lazy { Wearable.getDataClient(context) }

    private var coverUrl: String? = null
    private var coverBytes: ByteArray? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(controller.state, headphones) { state, headphones -> state.toWatchState(headphones) }
                .distinctUntilChanged()
                // Mehrere Änderungen kurz hintereinander (z. B. Lautstärke) zusammenfassen.
                .debounce(DEBOUNCE_MS)
                .collect { publish(it) }
        }
    }

    private suspend fun publish(state: WatchState) {
        try {
            val request = PutDataMapRequest.create(WearProtocol.STATE_PATH).apply {
                dataMap.putByteArray(WearProtocol.STATE_KEY, state.encode())
                cover(state.coverUrl)?.let { dataMap.putAsset(WearProtocol.COVER_ASSET, Asset.createFromBytes(it)) }
            }.asPutDataRequest().setUrgent()
            val item = dataClient.putDataItem(request).await()
            Log.d(TAG, "Zustand veröffentlicht: Raum=${state.selectedGroupId}, Titel=${state.title}, Cover=${request.assets.isNotEmpty()}, ${item.uri}")
        } catch (e: Exception) {
            // Ohne Google-Play-Dienste oder Uhr gibt es schlicht keine Uhr-Anbindung.
            Log.w(TAG, "Zustand für die Uhr nicht veröffentlicht", e)
        }
    }

    /** Lädt das Cover verkleinert als JPEG; die Uhr braucht nur ein kleines Bild. */
    private suspend fun cover(url: String?): ByteArray? {
        if (url == null) return null
        if (url == coverUrl) return coverBytes
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(COVER_SIZE_PX)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request)
        val bytes = (result as? SuccessResult)?.image?.toBitmap()?.let { bitmap ->
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        }
        coverUrl = url
        coverBytes = bytes
        return bytes
    }

    private companion object {
        const val TAG = "WearBridge"
        const val DEBOUNCE_MS = 150L
        const val COVER_SIZE_PX = 320
        const val JPEG_QUALITY = 85
    }
}
