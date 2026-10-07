package de.bernos.wearprotocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearProtocolTest {

    @Test
    fun `Zustand uebersteht Hin- und Rueckweg`() {
        val state = WatchState(
            groups = listOf(
                WatchGroup("RINCON_1:5", "Wohnzimmer + Küche", isPlaying = true),
                WatchGroup("RINCON_2:3", "Bad"),
            ),
            selectedGroupId = "RINCON_1:5",
            title = "You Oughta Know",
            artist = "Alanis Morissette",
            album = "STAR FM",
            coverUrl = "https://cdn.example.org/logo.png",
            isPlaying = true,
            volume = 23,
            muted = true,
            error = "Bei dieser Quelle nicht möglich",
            moveTargets = listOf(WatchRoom("RINCON_2", "Küche"), WatchRoom("RINCON_3", "Bad")),
            favorites = listOf(WatchFavorite("FV:2/3", "STAR FM Maximum Rock Berlin")),
        )

        val decoded = WatchState.decode(state.encode())

        assertEquals(state, decoded)
        assertEquals("Wohnzimmer + Küche", decoded!!.selectedGroup?.name)
    }

    @Test
    fun `leerer Zustand ohne Lautstaerke`() {
        assertEquals(WatchState(), WatchState.decode(WatchState().encode()))
    }

    @Test
    fun `alle Befehle ueberstehen Hin- und Rueckweg`() {
        val commands = listOf(
            WatchCommand.Hello,
            WatchCommand.Refresh,
            WatchCommand.SelectGroup("RINCON_1:5"),
            WatchCommand.PlayPause,
            WatchCommand.Next,
            WatchCommand.Previous,
            WatchCommand.SetVolume(42),
            WatchCommand.SetMuted(true),
            WatchCommand.SetMuted(false),
            WatchCommand.MoveTo("RINCON_3"),
            WatchCommand.PlayFavorite("FV:2/3"),
        )
        commands.forEach { assertEquals(it, WatchCommand.decode(it.encode())) }
    }

    @Test
    fun `Lautstaerke wird begrenzt`() {
        assertEquals(WatchCommand.SetVolume(100), WatchCommand.decode(WatchCommand.SetVolume(250).encode()))
    }

    @Test
    fun `fremde oder kaputte Daten ergeben null`() {
        assertNull(WatchState.decode(byteArrayOf()))
        assertNull(WatchState.decode(byteArrayOf(0, 0, 0, 99)))
        assertNull(WatchCommand.decode("kaputt".toByteArray()))
    }
}
