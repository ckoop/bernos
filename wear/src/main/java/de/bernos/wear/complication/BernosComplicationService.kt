package de.bernos.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import de.bernos.wear.MainActivity
import de.bernos.wear.NowPlayingSummary
import de.bernos.wear.R
import de.bernos.wear.phone

/** Komplikation fürs Zifferblatt: laufender Titel; ein Tipp öffnet Bernos. */
class BernosComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        build(request.complicationType, NowPlayingSummary.from(applicationContext.phone.awaitState()))

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, NowPlayingSummary(connected = true, room = "Wohnzimmer", title = "Get Lucky", subtitle = "Daft Punk", isPlaying = true))

    private fun build(type: ComplicationType, summary: NowPlayingSummary): ComplicationData? {
        val tapAction = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, if (summary.isPlaying) R.drawable.ic_music_note else R.drawable.ic_speaker),
        ).build()
        val fallback = getString(if (summary.room == null) R.string.app_name else R.string.nothing_playing)
        val description = text(listOfNotNull(summary.room, summary.longText).joinToString(": ").ifEmpty { fallback })

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(summary.title ?: summary.room ?: fallback), description)
                    .setMonochromaticImage(icon)
                    .setTapAction(tapAction)
                    .build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(text(summary.longText ?: fallback), description)
                    .setTitle(summary.room?.let { text(it) })
                    .setMonochromaticImage(icon)
                    .setTapAction(tapAction)
                    .build()
            else -> null
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()
}
