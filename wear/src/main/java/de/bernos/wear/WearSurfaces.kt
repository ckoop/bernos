package de.bernos.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import de.bernos.wear.complication.BernosComplicationService
import de.bernos.wear.tile.BernosTileService

/** Kachel und Komplikation neu zeichnen lassen, z. B. nach einem neuen Titel. */
object WearSurfaces {
    fun requestUpdate(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(BernosTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, BernosComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
