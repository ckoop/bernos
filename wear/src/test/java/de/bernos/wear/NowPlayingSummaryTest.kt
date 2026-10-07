package de.bernos.wear

import de.bernos.wearprotocol.WatchGroup
import de.bernos.wearprotocol.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSummaryTest {
    private val groups = listOf(WatchGroup("G1", "Wohnzimmer"), WatchGroup("G2", "Bad"))

    @Test
    fun `ohne Zustand vom Handy nicht verbunden`() {
        val summary = NowPlayingSummary.from(null)
        assertFalse(summary.connected)
        assertNull(summary.room)
    }

    @Test
    fun `ohne gewaehlten Raum verbunden, aber leer`() {
        val summary = NowPlayingSummary.from(WatchState(groups = groups))
        assertTrue(summary.connected)
        assertNull(summary.room)
        assertNull(summary.longText)
    }

    @Test
    fun `Titel und Kuenstler`() {
        val summary = NowPlayingSummary.from(
            WatchState(groups = groups, selectedGroupId = "G1", title = "Roscoe", artist = "Johnossi", album = "STAR FM", isPlaying = true),
        )
        assertEquals("Wohnzimmer", summary.room)
        assertEquals("Roscoe", summary.title)
        assertEquals("Johnossi", summary.subtitle)
        assertEquals("Roscoe – Johnossi", summary.longText)
        assertTrue(summary.isPlaying)
    }

    @Test
    fun `Radio-Werbung ohne Titel zeigt den Sendernamen`() {
        val summary = NowPlayingSummary.from(WatchState(groups = groups, selectedGroupId = "G1", album = "STAR FM"))
        assertEquals("STAR FM", summary.title)
        assertNull(summary.subtitle)
        assertEquals("STAR FM", summary.longText)
    }

    @Test
    fun `Radio ohne Kuenstler zeigt den Sender als Untertitel`() {
        val summary = NowPlayingSummary.from(WatchState(groups = groups, selectedGroupId = "G1", title = "Nachrichten", album = "STAR FM"))
        assertEquals("Nachrichten", summary.title)
        assertEquals("STAR FM", summary.subtitle)
    }
}
