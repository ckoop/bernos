package de.bernos.wear.tile

import android.util.Log
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import de.bernos.wear.NowPlayingSummary
import de.bernos.wear.R
import de.bernos.wear.phone
import de.bernos.wearprotocol.WatchCommand

/** Kachel neben dem Zifferblatt: Raum, Titel und Zurück/Abspielen/Weiter. */
class BernosTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val phone = applicationContext.phone
            Log.d("BernosTile", "Kachel angefordert, letzter Klick: '${requestParams.currentState.lastClickableId}'")
            // Ein Tipp auf einen Knopf der Kachel kommt als neue Anfrage mit dessen Kennung an.
            when (requestParams.currentState.lastClickableId) {
                TileLayout.ID_PLAY_PAUSE -> phone.send(WatchCommand.PlayPause)
                TileLayout.ID_NEXT -> phone.send(WatchCommand.Next)
                TileLayout.ID_PREVIOUS -> phone.send(WatchCommand.Previous)
            }
            val summary = NowPlayingSummary.from(phone.awaitState())
            val cover = phone.tileCover.value?.takeIf { summary.room != null }
            TileBuilders.Tile.Builder()
                // Neue Version bei neuem Cover, damit die Kachel die Bilder neu anfordert.
                .setResourcesVersion(cover?.version ?: RESOURCES_VERSION)
                // Keine regelmäßige Aktualisierung; neue Zustände vom Handy stoßen sie an.
                .setFreshnessIntervalMillis(0)
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(
                        TileLayout.build(this@BernosTileService, requestParams.deviceConfiguration, summary, hasCover = cover != null),
                    ),
                )
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = SuspendToFutureAdapter.launchFuture {
        val cover = applicationContext.phone.tileCover.value?.takeIf { it.version == requestParams.version }
        ResourceBuilders.Resources.Builder()
            .setVersion(requestParams.version)
            .apply {
                if (cover != null) {
                    addIdToImageMapping(
                        TileLayout.IMAGE_COVER,
                        ResourceBuilders.ImageResource.Builder()
                            .setInlineResource(
                                ResourceBuilders.InlineImageResource.Builder()
                                    .setData(cover.data)
                                    .setWidthPx(cover.widthPx)
                                    .setHeightPx(cover.heightPx)
                                    .setFormat(ResourceBuilders.IMAGE_FORMAT_RGB_565)
                                    .build(),
                            )
                            .build(),
                    )
                }
            }
            .addIdToImageMapping(TileLayout.ICON_PLAY, image(R.drawable.ic_play))
            .addIdToImageMapping(TileLayout.ICON_PAUSE, image(R.drawable.ic_pause))
            .addIdToImageMapping(TileLayout.ICON_NEXT, image(R.drawable.ic_skip_next))
            .addIdToImageMapping(TileLayout.ICON_PREVIOUS, image(R.drawable.ic_skip_previous))
            .build()
    }

    private fun image(resId: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build())
        .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
