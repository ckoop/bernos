package de.bernos.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.bernos.sonos.NowPlaying
import de.bernos.sonos.SonosDevice
import de.bernos.sonos.SonosState
import de.bernos.sonos.TrackInfo
import de.bernos.sonos.TransportState
import de.bernos.sonos.ZoneGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BernosScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val wohnzimmer = SonosDevice("RINCON_1", "Wohnzimmer", "192.168.1.20")
    private val kueche = SonosDevice("RINCON_2", "Küche", "192.168.1.21")
    private val bad = SonosDevice("RINCON_3", "Bad", "192.168.1.22")

    private val groups = listOf(
        ZoneGroup("G1", wohnzimmer, listOf(wohnzimmer, kueche)),
        ZoneGroup("G2", bad, listOf(bad)),
    )

    private class RecordingActions : BernosActions {
        val calls = mutableListOf<String>()
        override fun refresh() { calls += "refresh" }
        override fun addHost(host: String) { calls += "addHost:$host" }
        override fun selectGroup(groupId: String?) { calls += "select:$groupId" }
        override fun playPause() { calls += "playPause" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun setVolume(volume: Int) { calls += "volume:$volume" }
        override fun setRoomVolume(roomUuid: String, volume: Int) { calls += "roomVolume:$roomUuid:$volume" }
        override fun addRoom(roomUuid: String) { calls += "add:$roomUuid" }
        override fun removeRoom(roomUuid: String) { calls += "remove:$roomUuid" }
        override fun moveTo(roomUuid: String) { calls += "move:$roomUuid" }
    }

    private fun show(state: SonosState): RecordingActions {
        val actions = RecordingActions()
        compose.setContent { MaterialTheme { BernosScreen(state, actions) } }
        return actions
    }

    @Test
    fun raumliste_zeigt_gruppen_und_waehlt_aus() {
        val actions = show(SonosState(groups = groups))

        compose.onNodeWithText("Wohnzimmer + Küche").assertIsDisplayed()
        compose.onNodeWithText("2 Lautsprecher").assertIsDisplayed()
        compose.onNodeWithText("Bad").performClick()

        assertEquals(listOf("select:G2"), actions.calls)
    }

    @Test
    fun ohne_lautsprecher_kann_eine_ip_eingegeben_werden() {
        show(SonosState(groups = emptyList(), error = "Keine Sonos-Lautsprecher gefunden."))

        compose.onNodeWithText("Keine Sonos-Lautsprecher gefunden.").assertIsDisplayed()
        compose.onNodeWithText("IP-Adresse eines Lautsprechers").assertIsDisplayed()
    }

    @Test
    fun wiedergabe_zeigt_titel_und_steuert_raeume() {
        val nowPlaying = NowPlaying(
            groupId = "G1",
            transportState = TransportState.PLAYING,
            track = TrackInfo("Get Lucky", "Daft Punk", "Random Access Memories", null),
            // Ohne Dauer keine Fortschrittsanzeige, deren Zeitschleife den Test nie ruhen ließe.
            durationMs = null,
            positionMs = null,
            positionCapturedAtMs = System.currentTimeMillis(),
            volume = 30,
            muted = false,
            memberVolumes = mapOf("RINCON_1" to 30, "RINCON_2" to 25),
        )
        val actions = show(SonosState(groups = groups, selectedGroupId = "G1", nowPlaying = nowPlaying))

        compose.onNodeWithText("Get Lucky").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Daft Punk").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Räume in dieser Gruppe").performScrollTo()
        compose.onNodeWithText("steuert").performScrollTo().assertIsDisplayed()
        // Nur die Küche lässt sich entfernen, nicht der steuernde Raum.
        assertEquals(1, compose.onAllNodesWithText("Entfernen").fetchSemanticsNodes().size)
        compose.onNodeWithText("Entfernen").performScrollTo().performClick()

        compose.onNodeWithText("Hierher").performScrollTo().performClick()
        compose.onNodeWithText("Dazu").performScrollTo().performClick()

        assertEquals(listOf("remove:RINCON_2", "move:RINCON_3", "add:RINCON_3"), actions.calls)
    }

    @Test
    fun abspielen_knopf_loest_aktion_aus() {
        val nowPlaying = NowPlaying("G2", TransportState.PAUSED, null, null, null, 0, 10, false)
        val actions = show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying))

        compose.onNodeWithText("Nichts wird abgespielt").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Abspielen")).performScrollTo().performClick()

        assertEquals(listOf("playPause"), actions.calls)
    }
}
