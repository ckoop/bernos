package de.bernos.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.bernos.app.BuildConfig
import de.bernos.app.bluetooth.HeadphoneBattery
import de.bernos.sonos.BatteryStatus
import de.bernos.sonos.Favorite
import de.bernos.sonos.GroupPlayback
import de.bernos.sonos.HomeTheaterState
import de.bernos.sonos.NowPlaying
import de.bernos.sonos.SonosDevice
import de.bernos.sonos.SonosState
import de.bernos.sonos.TrackInfo
import de.bernos.sonos.TransportState
import de.bernos.sonos.ZoneGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        override fun toggleMute() { calls += "toggleMute" }
        override fun setSleepTimer(minutes: Int?) { calls += "sleep:$minutes" }
        override fun setRoomVolume(roomUuid: String, volume: Int) { calls += "roomVolume:$roomUuid:$volume" }
        override fun addRoom(roomUuid: String) { calls += "add:$roomUuid" }
        override fun removeRoom(roomUuid: String) { calls += "remove:$roomUuid" }
        override fun moveTo(roomUuid: String) { calls += "move:$roomUuid" }
        override fun loadFavorites() { calls += "loadFavorites" }
        override fun playFavorite(favoriteId: String) { calls += "favorite:$favoriteId" }
        override fun switchToTv() { calls += "tv" }
        override fun setNightMode(enabled: Boolean) { calls += "night:$enabled" }
        override fun setSpeechEnhancement(enabled: Boolean) { calls += "speech:$enabled" }
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
    fun raumliste_zeigt_die_versionsnummer() {
        show(SonosState(groups = groups))

        compose.onNodeWithText("Bernos ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        assertTrue(Regex("\\d+\\.\\d+\\.\\d+").matches(BuildConfig.VERSION_NAME))
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

        assertEquals(listOf("loadFavorites", "remove:RINCON_2", "move:RINCON_3", "add:RINCON_3"), actions.calls)
    }

    @Test
    fun abspielen_knopf_loest_aktion_aus() {
        val nowPlaying = NowPlaying("G2", TransportState.PAUSED, null, null, null, 0, 10, false)
        val actions = show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying))

        compose.onNodeWithText("Nichts wird abgespielt").performScrollTo().assertIsDisplayed()
        compose.onNode(hasContentDescription("Abspielen")).performScrollTo().performClick()

        assertEquals(listOf("loadFavorites", "playPause"), actions.calls)
    }

    @Test
    fun favoriten_werden_angezeigt_und_abgespielt() {
        val favorites = listOf(
            Favorite("FV:2/0", "Aktuell angesagt", uri = null, metadata = null, albumArtUrl = null, description = "Sonos Radio"),
            Favorite("FV:2/3", "STAR FM Maximum Rock Berlin", "x-sonosapi-stream:tunein%3A5229", "<DIDL-Lite/>", null, "TuneIn"),
        )
        val nowPlaying = NowPlaying("G2", TransportState.PAUSED, null, null, null, 0, 10, false)
        val actions = show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying, favorites = favorites))

        compose.onNodeWithText("Favoriten").performScrollTo().assertIsDisplayed()
        // Verknüpfungen lassen sich nicht abspielen und erscheinen nur als Hinweis.
        assertEquals(0, compose.onAllNodesWithText("Aktuell angesagt").fetchSemanticsNodes().size)
        compose.onNodeWithText("1 Verknüpfung lässt sich nur in der Sonos-App öffnen.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("STAR FM Maximum Rock Berlin").performScrollTo().performClick()

        assertEquals(listOf("loadFavorites", "favorite:FV:2/3"), actions.calls)
    }

    @Test
    fun stummschalten_knopf_zeigt_zustand_und_loest_aktion_aus() {
        val laut = NowPlaying("G2", TransportState.PLAYING, null, null, null, 0, 40, muted = false)
        val actions = show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = laut))

        compose.onNodeWithContentDescription("Stummschalten").performScrollTo().performClick()

        assertEquals(listOf("loadFavorites", "toggleMute"), actions.calls)
    }

    @Test
    fun stumm_zeigt_knopf_zum_aufheben() {
        val stumm = NowPlaying("G2", TransportState.PLAYING, null, null, null, 0, 40, muted = true)
        show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = stumm))

        compose.onNodeWithContentDescription("Stummschaltung aufheben").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun raumliste_zeigt_was_laeuft_und_den_akku() {
        val state = SonosState(
            groups = groups,
            groupPlayback = mapOf(
                "G1" to GroupPlayback(TransportState.PLAYING, TrackInfo("Roscoe", "Johnossi", "STAR FM", null)),
                "G2" to GroupPlayback(TransportState.PAUSED, TrackInfo("Get Lucky", "Daft Punk", null, null)),
            ),
            batteries = mapOf("RINCON_3" to BatteryStatus(level = 15, charging = false)),
        )
        show(state)

        compose.onNodeWithText("▶ Roscoe · Johnossi").assertIsDisplayed()
        compose.onNodeWithText("Get Lucky · Daft Punk").assertIsDisplayed()
        compose.onNodeWithText("Akku 15 %").assertIsDisplayed()
    }

    @Test
    fun raumliste_zeigt_akku_der_sonos_ace() {
        compose.setContent { MaterialTheme { BernosScreen(SonosState(groups = groups), RecordingActions(), HeadphoneBattery("Sonos Ace", 64)) } }

        compose.onNodeWithText("Sonos Ace").assertIsDisplayed()
        compose.onNodeWithText("Akku 64 %").assertIsDisplayed()
    }

    @Test
    fun schlaftimer_auswaehlen_und_restzeit_anzeigen() {
        val nowPlaying = NowPlaying("G2", TransportState.PLAYING, null, null, null, 0, 40, false)
        val actions = show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying))

        compose.onNodeWithText("Schlaftimer").performScrollTo().performClick()
        compose.onNodeWithText("30 Minuten").performClick()

        assertEquals(listOf("loadFavorites", "sleep:30"), actions.calls)
    }

    @Test
    fun laufender_schlaftimer_zeigt_restzeit_und_laesst_sich_ausschalten() {
        val nowPlaying = NowPlaying("G2", TransportState.PLAYING, null, null, null, 0, 40, false, sleepTimerRemainingMs = 29 * 60_000L + 30_000L)
        val actions = show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying))

        compose.onNodeWithText("Aus in 30 Min.").performScrollTo().performClick()
        compose.onNodeWithText("Schlaftimer aus").performClick()

        assertEquals(listOf("loadFavorites", "sleep:null"), actions.calls)
    }

    @Test
    fun soundbar_tv_ton_nachtmodus_und_sprachverbesserung() {
        val homeTheater = HomeTheaterState("RINCON_3", tvActive = false, nightMode = false, speechEnhancement = true)
        val nowPlaying = NowPlaying("G2", TransportState.PLAYING, null, null, null, 0, 40, false, homeTheater = homeTheater)
        val actions = show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying))

        compose.onNodeWithText("Beendet die Musik in diesem Raum").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nachtmodus").performScrollTo().performClick()
        compose.onNodeWithTag("sprachverbesserung").performScrollTo().performClick()
        compose.onNodeWithTag("tv-ton").performScrollTo().performClick()

        assertEquals(listOf("loadFavorites", "night:true", "speech:false", "tv"), actions.calls)
    }

    @Test
    fun laufender_fernseher_sperrt_den_tv_knopf() {
        val homeTheater = HomeTheaterState("RINCON_3", tvActive = true, nightMode = true, speechEnhancement = false)
        val tv = TrackInfo("Fernseher", null, null, null)
        val nowPlaying = NowPlaying("G2", TransportState.PLAYING, tv, null, null, 0, 40, false, homeTheater = homeTheater)
        show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying))

        compose.onNodeWithTag("tv-ton").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Fernsehton läuft").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ohne_soundbar_kein_tv_bereich() {
        val nowPlaying = NowPlaying("G2", TransportState.PLAYING, null, null, null, 0, 40, false)
        show(SonosState(groups = groups, selectedGroupId = "G2", nowPlaying = nowPlaying))

        compose.onNodeWithTag("tv-ton").assertDoesNotExist()
    }
}
