package de.bernos.app.wear

import de.bernos.sonos.NowPlaying
import de.bernos.sonos.SonosDevice
import de.bernos.sonos.SonosState
import de.bernos.sonos.TrackInfo
import de.bernos.sonos.TransportState
import de.bernos.sonos.ZoneGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStateMapperTest {
    private val wohnzimmer = SonosDevice("RINCON_1", "Wohnzimmer", "192.168.1.10")
    private val kueche = SonosDevice("RINCON_2", "Küche", "192.168.1.11")
    private val bad = SonosDevice("RINCON_3", "Bad", "192.168.1.12")
    private val groups = listOf(
        ZoneGroup("G1", wohnzimmer, listOf(wohnzimmer, kueche)),
        ZoneGroup("G2", bad, listOf(bad)),
    )

    @Test
    fun `Gruppen alphabetisch, gewaehlte Gruppe mit Titel, Cover und Lautstaerke`() {
        val state = SonosState(
            groups = groups,
            selectedGroupId = "G1",
            nowPlaying = NowPlaying(
                groupId = "G1",
                transportState = TransportState.PLAYING,
                track = TrackInfo("You Oughta Know", "Alanis Morissette", "STAR FM", "https://cdn.example.org/logo.png"),
                durationMs = null,
                positionMs = null,
                positionCapturedAtMs = 0,
                volume = 23,
                muted = false,
            ),
        )

        val watch = state.toWatchState()

        assertEquals(listOf("Bad", "Wohnzimmer + Küche"), watch.groups.map { it.name })
        assertTrue(watch.groups.first { it.id == "G1" }.isPlaying)
        assertFalse(watch.groups.first { it.id == "G2" }.isPlaying)
        assertEquals("You Oughta Know", watch.title)
        assertEquals("Alanis Morissette", watch.artist)
        assertEquals("STAR FM", watch.album)
        assertEquals("https://cdn.example.org/logo.png", watch.coverUrl)
        assertTrue(watch.isPlaying)
        assertEquals(23, watch.volume)
    }

    @Test
    fun `veraltete Wiedergabe einer anderen Gruppe wird nicht gezeigt`() {
        val state = SonosState(
            groups = groups,
            selectedGroupId = "G2",
            nowPlaying = NowPlaying("G1", TransportState.PLAYING, TrackInfo("Alt", null, null, null), null, null, 0, 50, false),
        )

        val watch = state.toWatchState()

        assertNull(watch.title)
        assertNull(watch.volume)
        assertFalse(watch.isPlaying)
    }
}
