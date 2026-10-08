package de.bernos.app.wear

import de.bernos.app.bluetooth.HeadphoneBattery
import de.bernos.sonos.BatteryStatus
import de.bernos.sonos.Favorite
import de.bernos.sonos.GroupPlayback
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
                muted = true,
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
        assertTrue(watch.muted)
        // Alle Räume außer dem steuernden, auch Mitglieder der eigenen Gruppe.
        assertEquals(listOf("Bad", "Küche"), watch.moveTargets.map { it.name })
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

    @Test
    fun `Uebersicht, Akku und Schlaftimer gehen an die Uhr`() {
        val state = SonosState(
            groups = groups,
            selectedGroupId = "G1",
            nowPlaying = NowPlaying("G1", TransportState.PLAYING, null, null, null, 0, 20, false, sleepTimerRemainingMs = 24 * 60_000L + 1),
            groupPlayback = mapOf("G2" to GroupPlayback(TransportState.PLAYING, TrackInfo("Roscoe", "Johnossi", null, null))),
            batteries = mapOf("RINCON_3" to BatteryStatus(80, charging = true)),
        )
        val watch = state.toWatchState()
        val bad = watch.groups.first { it.id == "G2" }

        assertTrue(bad.isPlaying)
        assertEquals("Roscoe · Johnossi", bad.nowPlaying)
        assertEquals(80, bad.batteryLevel)
        assertTrue(bad.charging)
        assertNull(watch.groups.first { it.id == "G1" }.batteryLevel)
        assertEquals(25, watch.sleepTimerMinutes)
    }

    @Test
    fun `nur abspielbare Favoriten gehen an die Uhr`() {
        val state = SonosState(
            groups = groups,
            favorites = listOf(
                Favorite("FV:2/0", "Aktuell angesagt", null, null, null, "Sonos Radio"),
                Favorite("FV:2/3", "STAR FM", "x-sonosapi-stream:tunein%3A5229", "<DIDL-Lite/>", null, "TuneIn"),
            ),
        )
        assertEquals(listOf("STAR FM"), state.toWatchState().favorites.map { it.title })
    }

    @Test
    fun `ohne gewaehlte Gruppe gibt es keine Ziele zum Verschieben`() {
        assertEquals(emptyList<Any>(), SonosState(groups = groups).toWatchState().moveTargets)
    }

    @Test
    fun `Sonos Ace wird mit Akkustand uebernommen`() {
        val watch = SonosState(groups = groups).toWatchState(HeadphoneBattery("Sonos Ace", 64))

        assertEquals("Sonos Ace", watch.headphones?.name)
        assertEquals(64, watch.headphones?.batteryLevel)
        assertNull(SonosState(groups = groups).toWatchState().headphones)
    }
}
