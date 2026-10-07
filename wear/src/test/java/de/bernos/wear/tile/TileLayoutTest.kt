package de.bernos.wear.tile

import android.annotation.SuppressLint
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.protolayout.DeviceParametersBuilders
import de.bernos.wear.NowPlayingSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Prüft den Aufbau der Kachel über ihre Protobuf-Darstellung (Texte und Knopf-Kennungen). */
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav", application = Application::class)
class TileLayoutTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val device = DeviceParametersBuilders.DeviceParameters.Builder()
        .setScreenWidthDp(227)
        .setScreenHeightDp(227)
        .setScreenDensity(2f)
        .setScreenShape(DeviceParametersBuilders.SCREEN_SHAPE_ROUND)
        .build()

    private fun layout(summary: NowPlayingSummary) = TileLayout.build(context, device, summary).toLayoutElementProto().toString()

    @Test
    fun `laufender Titel mit Steuerknoepfen`() {
        val text = layout(NowPlayingSummary(true, "Wohnzimmer", "Roscoe", "Johnossi", isPlaying = true))

        listOf("Wohnzimmer", "Roscoe", "Johnossi", TileLayout.ID_PREVIOUS, TileLayout.ID_PLAY_PAUSE, TileLayout.ID_NEXT, TileLayout.ICON_PAUSE)
            .forEach { assertTrue("$it fehlt", text.contains(it)) }
        assertFalse(text.contains("\"${TileLayout.ICON_PLAY}\""))
    }

    @Test
    fun `pausiert zeigt Abspielen`() {
        val text = layout(NowPlayingSummary(true, "Bad", "Song", null, isPlaying = false))
        assertTrue(text.contains("\"${TileLayout.ICON_PLAY}\""))
    }

    @Test
    fun `ohne Raum Hinweis und Knopf zum Oeffnen`() {
        val text = layout(NowPlayingSummary(true, null, null, null, isPlaying = false))
        // Umlaute stehen in der Protobuf-Textdarstellung maskiert.
        assertTrue(text.contains("Kein Raum"))
        assertTrue(text.contains(TileLayout.ID_OPEN))
        assertFalse(text.contains(TileLayout.ID_PLAY_PAUSE))
    }
}
