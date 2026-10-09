package de.bernos.wear.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.bernos.wear.BuildConfig
import de.bernos.wear.PhoneConnection
import de.bernos.wearprotocol.WatchFavorite
import de.bernos.wearprotocol.WatchGroup
import de.bernos.wearprotocol.WatchHeadphones
import de.bernos.wearprotocol.WatchRoom
import de.bernos.wearprotocol.WatchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Oberflächentests auf einer simulierten runden Uhr; ohne echte Data Layer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav", application = Application::class)
class BernosWearTest {

    @get:Rule
    val compose = createComposeRule()

    private val groups = listOf(
        WatchGroup("G1", "Wohnzimmer + Küche", isPlaying = true),
        WatchGroup("G2", "Bad"),
    )

    private class RecordingActions : WearActions {
        val calls = mutableListOf<String>()
        override fun selectGroup(groupId: String) { calls += "select:$groupId" }
        override fun playPause() { calls += "playPause" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun setVolume(volume: Int) { calls += "volume:$volume" }
        override fun setMuted(muted: Boolean) { calls += "muted:$muted" }
        override fun setSleepTimer(minutes: Int) { calls += "sleep:$minutes" }
        override fun moveTo(roomUuid: String) { calls += "move:$roomUuid" }
        override fun playFavorite(favoriteId: String) { calls += "favorite:$favoriteId" }
        override fun refresh() { calls += "refresh" }
        override fun reconnect() { calls += "reconnect" }
    }

    @Test
    fun `ohne gewaehlten Raum erscheint die Raumliste`() {
        val actions = RecordingActions()
        compose.setContent { BernosWear(WatchState(groups = groups), null, PhoneConnection.CONNECTED, actions) }

        compose.onNodeWithText("Bad").assertIsDisplayed()
        compose.onNodeWithText("Wohnzimmer + Küche").assertIsDisplayed()
        compose.onNodeWithText("Bad").performClick()

        assertEquals(listOf("select:G2"), actions.calls)
    }

    @Test
    fun `Wiedergabe zeigt Titel und steuert`() {
        val actions = RecordingActions()
        val state = WatchState(
            groups = groups,
            selectedGroupId = "G1",
            title = "You Oughta Know",
            artist = "Alanis Morissette",
            album = "STAR FM",
            isPlaying = true,
            volume = 23,
        )
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, actions) }

        compose.onNodeWithText("You Oughta Know").assertIsDisplayed()
        compose.onNodeWithText("Alanis Morissette").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lautstärke 23").assertExists()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Nächster Titel").performClick()
        compose.onNodeWithContentDescription("Vorheriger Titel").performClick()

        assertEquals(listOf("playPause", "next", "previous"), actions.calls)
    }

    @Test
    fun `Tipp auf den Raumnamen oeffnet die Raumliste`() {
        val actions = RecordingActions()
        val state = WatchState(groups = groups, selectedGroupId = "G1", title = "Song", isPlaying = false, volume = 10)
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, actions) }

        compose.onNodeWithTag("raum").performClick()
        compose.onNodeWithText("Bad").performClick()

        assertEquals(listOf("select:G2"), actions.calls)
    }

    @Test
    fun `Musik in einen anderen Raum verschieben`() {
        val actions = RecordingActions()
        val state = WatchState(
            groups = groups,
            selectedGroupId = "G1",
            title = "Song",
            isPlaying = true,
            volume = 10,
            moveTargets = listOf(WatchRoom("RINCON_3", "Bad")),
        )
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, actions) }

        compose.onNodeWithTag("raum").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("verschieben-RINCON_3"))
        compose.onNodeWithText("Musik hierher verschieben").assertIsDisplayed()
        compose.onNodeWithTag("verschieben-RINCON_3").performClick()

        assertEquals(listOf("move:RINCON_3"), actions.calls)
        compose.onNodeWithText("Song").assertIsDisplayed()
    }

    @Test
    fun `Favorit abspielen`() {
        val actions = RecordingActions()
        val state = WatchState(
            groups = groups,
            selectedGroupId = "G1",
            title = "Ein sehr langer Liedtitel, der zwei Zeilen braucht",
            artist = "Dexter And The Moonrocks",
            volume = 10,
            favorites = listOf(WatchFavorite("FV:2/3", "STAR FM Maximum Rock Berlin")),
        )
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, actions) }

        // Auf der echten Uhr war der Knopf unten aus dem Bildschirm gerutscht; daher auf Sichtbarkeit prüfen,
        // und zwar mit langem Titel und Künstler, wie es im Alltag vorkommt.
        compose.onNodeWithContentDescription("Nächster Titel").assertIsDisplayed()
        compose.onNodeWithTag("favoriten").assertIsDisplayed().performClick()
        compose.onNodeWithText("STAR FM Maximum Rock Berlin").performClick()

        assertEquals(listOf("favorite:FV:2/3"), actions.calls)
        compose.onNodeWithText("Dexter And The Moonrocks").assertIsDisplayed()
    }

    @Test
    fun `Stern oben und Raum unten liegen mittig im runden Bildschirm`() {
        val state = WatchState(
            groups = listOf(WatchGroup("G1", "Wohnzimmer + Küche + Schlafzimmer", isPlaying = true)),
            selectedGroupId = "G1",
            title = "Ein sehr langer Liedtitel, der zwei Zeilen braucht",
            artist = "Dexter And The Moonrocks",
            isPlaying = true,
            volume = 10,
            favorites = listOf(WatchFavorite("FV:2/3", "STAR FM")),
        )
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, RecordingActions()) }

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val center = (root.left + root.right) / 2
        val radius = (root.right - root.left) / 2
        val stern = compose.onNodeWithTag("favoriten").getUnclippedBoundsInRoot()
        val abspielen = compose.onNodeWithTag("abspielen").getUnclippedBoundsInRoot()
        val raum = compose.onNodeWithTag("raum").getUnclippedBoundsInRoot()

        // Nicht zusammengedrückt (der Platz reicht für alles).
        assertTrue(raum.bottom - raum.top >= 32.dp && stern.bottom - stern.top >= 32.dp)
        // Senkrecht übereinander und auf einer Achse mit Abspielen.
        assertTrue(stern.bottom <= abspielen.top && abspielen.bottom <= raum.top)
        listOf(stern, raum).forEach { assertEquals(center.value, ((it.left + it.right) / 2).value, 1f) }
        // Alle Ecken innerhalb des Kreises, sonst schneidet der runde Rand sie ab. Robolectric misst Text
        // viel zu schmal, daher den Raumknopf mit seiner Höchstbreite von 150 dp ansetzen.
        val raumBreit = raum.copy(left = center - 75.dp, right = center + 75.dp)
        listOf(stern, raumBreit, abspielen).forEach { box ->
            listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom).forEach { (x, y) ->
                val dx = (x - center).value
                val dy = (y - center).value
                assertTrue("Ecke ($x, $y) außerhalb des Kreises", dx * dx + dy * dy <= radius.value * radius.value)
            }
        }
    }

    @Test
    fun `ohne Favoriten kein Favoriten-Knopf`() {
        val state = WatchState(groups = groups, selectedGroupId = "G1", title = "Song", volume = 10)
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, RecordingActions()) }

        compose.onNodeWithText("Song").assertIsDisplayed()
        compose.onNodeWithTag("favoriten").assertDoesNotExist()
    }

    @Test
    fun `neuer Zustand vom Handy erscheint sofort`() {
        // Auf der echten Uhr blieb der erste Zustand stehen, weil die Navigation ihn eingefangen hatte.
        val actions = RecordingActions()
        var state by mutableStateOf<WatchState?>(null)
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTING, actions) }
        compose.onNodeWithText("Verbinde mit dem Handy …").assertIsDisplayed()

        state = WatchState(groups = groups)
        compose.onNodeWithText("Bad").assertIsDisplayed()

        state = WatchState(groups = groups, selectedGroupId = "G2", title = "Song B", volume = 30)
        compose.onNodeWithText("Song B").assertIsDisplayed()

        state = state!!.copy(title = "Song C", isPlaying = true)
        compose.onNodeWithText("Song C").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()

        state = state!!.copy(selectedGroupId = null)
        compose.onNodeWithText("Wohnzimmer + Küche").assertIsDisplayed()
    }

    @Test
    fun `Raumliste zeigt am Ende die Versionsnummer`() {
        compose.setContent { BernosWear(WatchState(groups = groups), null, PhoneConnection.CONNECTED, RecordingActions()) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("version"))
        compose.onNodeWithText("Bernos ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
    }

    @Test
    fun `langes Druecken auf Abspielen schaltet stumm`() {
        val actions = RecordingActions()
        val laut = WatchState(groups = groups, selectedGroupId = "G1", title = "Song", isPlaying = true, volume = 30)
        compose.setContent { BernosWear(laut, null, PhoneConnection.CONNECTED, actions) }

        compose.onNodeWithTag("stumm").assertDoesNotExist()
        compose.onNodeWithTag("abspielen").performTouchInput { longClick() }

        assertEquals(listOf("muted:true"), actions.calls)
    }

    @Test
    fun `stumm zeigt Zeichen und hebt per langem Druecken auf`() {
        val actions = RecordingActions()
        val stumm = WatchState(groups = groups, selectedGroupId = "G1", title = "Song", isPlaying = true, volume = 30, muted = true)
        compose.setContent { BernosWear(stumm, null, PhoneConnection.CONNECTED, actions) }

        compose.onNodeWithTag("stumm").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stumm, Lautstärke 30").assertExists()
        compose.onNodeWithTag("abspielen").performTouchInput { longClick() }

        assertEquals(listOf("muted:false"), actions.calls)
    }

    @Test
    fun `Raumliste zeigt was laeuft und den Akku`() {
        val state = WatchState(
            groups = listOf(
                WatchGroup("G1", "Wohnzimmer", isPlaying = true, nowPlaying = "Roscoe · Johnossi"),
                WatchGroup("G2", "Sonos Roam", nowPlaying = "Get Lucky", batteryLevel = 80, charging = false),
            ),
        )
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, RecordingActions()) }

        compose.onNodeWithText("▶ Roscoe · Johnossi").assertIsDisplayed()
        compose.onNodeWithText("Get Lucky · Akku 80 %").assertIsDisplayed()
    }

    @Test
    fun `Raumliste zeigt den Akku der Sonos Ace`() {
        val state = WatchState(groups = groups, headphones = WatchHeadphones("Sonos Ace", 64))
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, RecordingActions()) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("kopfhoerer"))
        compose.onNodeWithText("Sonos Ace · Akku 64 %").assertIsDisplayed()
    }

    @Test
    fun `Schlaftimer ueber die Raumliste stellen`() {
        val actions = RecordingActions()
        val state = WatchState(groups = groups, selectedGroupId = "G1", title = "Song", isPlaying = true, volume = 30, sleepTimerMinutes = 12)
        compose.setContent { BernosWear(state, null, PhoneConnection.CONNECTED, actions) }

        compose.onNodeWithTag("raum").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("schlaftimer"))
        compose.onNodeWithText("Aus in 12 Min.").assertIsDisplayed()
        compose.onNodeWithTag("schlaftimer").performClick()
        compose.onNodeWithText("30 Minuten").performClick()

        assertEquals(listOf("sleep:30"), actions.calls)
        compose.onNodeWithText("Song").assertIsDisplayed()
    }

    @Test
    fun `Handy nicht erreichbar`() {
        val actions = RecordingActions()
        compose.setContent { BernosWear(null, null, PhoneConnection.UNREACHABLE, actions) }

        compose.onNodeWithText("Handy nicht erreichbar").assertIsDisplayed()
        compose.onNodeWithText("Erneut versuchen").performClick()

        assertEquals(listOf("reconnect"), actions.calls)
    }
}
