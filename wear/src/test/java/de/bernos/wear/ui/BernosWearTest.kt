package de.bernos.wear.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.bernos.wear.PhoneConnection
import de.bernos.wearprotocol.WatchGroup
import de.bernos.wearprotocol.WatchState
import org.junit.Assert.assertEquals
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
    fun `Handy nicht erreichbar`() {
        val actions = RecordingActions()
        compose.setContent { BernosWear(null, null, PhoneConnection.UNREACHABLE, actions) }

        compose.onNodeWithText("Handy nicht erreichbar").assertIsDisplayed()
        compose.onNodeWithText("Erneut versuchen").performClick()

        assertEquals(listOf("reconnect"), actions.calls)
    }
}
