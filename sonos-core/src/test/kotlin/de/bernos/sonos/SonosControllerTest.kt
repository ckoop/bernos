package de.bernos.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ende-zu-Ende-Test des Controllers gegen ein simuliertes Sonos-System mit drei Räumen. */
class SonosControllerTest {
    private lateinit var fake: FakeSonosSystem
    private lateinit var scope: CoroutineScope
    private lateinit var controller: SonosController

    @Before
    fun setUp() {
        fake = FakeSonosSystem(listOf("Wohnzimmer", "Küche", "Bad"))
        fake.speaker("Wohnzimmer").apply {
            transport = "PLAYING"
            title = "Song A"
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = SonosController(scope, groupingRetryDelayMs = 10)
    }

    @After
    fun tearDown() {
        controller.stopTracking()
        scope.cancel()
        fake.close()
    }

    private suspend fun awaitState(description: String, predicate: (SonosState) -> Boolean): SonosState =
        try {
            withTimeout(10_000) { controller.state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Zustand nicht erreicht: $description\nletzter Zustand: ${controller.state.value}", e)
        }

    private fun uuid(room: String) = fake.speaker(room).uuid

    @Test
    fun `findet Raeume per Adresse und zeigt den laufenden Titel`() = runBlocking {
        controller.addHost(fake.speaker("Küche").address)
        val state = awaitState("drei Räume") { it.groups.size == 3 }
        assertEquals(listOf("Bad", "Küche", "Wohnzimmer"), state.rooms.map { it.roomName })

        controller.selectGroup(state.groups.first { it.coordinator.roomName == "Wohnzimmer" }.id)
        val playing = awaitState("Song A läuft") { it.nowPlaying?.track?.title == "Song A" }
        assertTrue(playing.nowPlaying!!.isPlaying)
        assertEquals("Testband", playing.nowPlaying!!.track?.artist)
        assertEquals(180_000L, playing.nowPlaying!!.durationMs)
    }

    @Test
    fun `Radio - Cover ist das Senderlogo, auch waehrend der Werbung`() = runBlocking {
        val logo = "https://cdn.example.org/starfm.png"
        val wohnzimmer = fake.speaker("Wohnzimmer")
        // Während der TuneIn-Werbung gibt es noch keinen streamContent.
        wohnzimmer.radio = FakeSonosSystem.Radio("STAR FM", logo, streamContent = null)
        controller.addHost(wohnzimmer.address)
        val state = awaitState("drei Räume") { it.groups.size == 3 }
        controller.selectGroup(state.groups.first { it.coordinator.roomName == "Wohnzimmer" }.id)

        val werbung = awaitState("Senderlogo") { it.nowPlaying?.track?.albumArtUrl == logo }
        assertEquals(null, werbung.nowPlaying!!.track?.title)
        assertEquals("STAR FM", werbung.nowPlaying!!.track?.album)

        wohnzimmer.radio = FakeSonosSystem.Radio("STAR FM", logo, streamContent = "Alanis Morissette - You Oughta Know")
        val song = awaitState("Titel vom Sender") { it.nowPlaying?.track?.title == "You Oughta Know" }
        assertEquals("Alanis Morissette", song.nowPlaying!!.track?.artist)
        assertEquals("STAR FM", song.nowPlaying!!.track?.album)
        assertEquals(logo, song.nowPlaying!!.track?.albumArtUrl)
    }

    @Test
    fun `gruppieren, Raumlautstaerke, Musik verschieben und Raum entfernen`() = runBlocking {
        controller.addHost(fake.speaker("Wohnzimmer").address)
        val initial = awaitState("drei Räume") { it.groups.size == 3 }
        controller.selectGroup(initial.groups.first { it.coordinator.roomName == "Wohnzimmer" }.id)
        awaitState("Song A läuft") { it.nowPlaying?.track?.title == "Song A" }

        // Küche dazunehmen
        controller.addRoomToGroup(uuid("Küche"))
        awaitState("Wohnzimmer + Küche") { s ->
            s.selectedGroup?.members?.map { it.roomName }?.toSet() == setOf("Wohnzimmer", "Küche")
        }
        assertEquals(uuid("Wohnzimmer"), fake.speaker("Küche").coordinatorUuid)
        awaitState("Lautstärke beider Räume") { it.nowPlaying?.memberVolumes?.size == 2 }

        // Lautstärke nur in der Küche ändern
        controller.setRoomVolume(uuid("Küche"), 42)
        withTimeout(5_000) { while (fake.speaker("Küche").volume != 42) delay(20) }
        assertEquals(20, fake.speaker("Wohnzimmer").volume)

        // Musik ins Bad verschieben: Bad übernimmt, Wohnzimmer verstummt, Küche bleibt dabei
        controller.movePlaybackTo(uuid("Bad"))
        val moved = awaitState("Bad steuert") { s ->
            s.selectedGroup?.coordinator?.roomName == "Bad" && s.nowPlaying?.track?.title == "Song A"
        }
        assertEquals(setOf("Bad", "Küche"), moved.selectedGroup!!.members.map { it.roomName }.toSet())
        assertEquals("STOPPED", fake.speaker("Wohnzimmer").transport)
        assertEquals(uuid("Wohnzimmer"), fake.speaker("Wohnzimmer").coordinatorUuid)

        // Küche wieder herausnehmen
        controller.removeRoomFromGroup(uuid("Küche"))
        awaitState("Bad allein") { s -> s.selectedGroup?.members?.map { it.roomName } == listOf("Bad") }
        assertEquals(3, controller.state.value.groups.size)
    }

    @Test
    fun `steuernder Raum laesst sich nicht entfernen`() = runBlocking {
        controller.addHost(fake.speaker("Wohnzimmer").address)
        val initial = awaitState("drei Räume") { it.groups.size == 3 }
        controller.selectGroup(initial.groups.first { it.coordinator.roomName == "Wohnzimmer" }.id)

        controller.removeRoomFromGroup(uuid("Wohnzimmer"))

        val state = awaitState("Fehlermeldung") { it.error != null }
        assertTrue(state.error!!.contains("steuert die Gruppe"))
        assertTrue(fake.calls.none { it.endsWith("BecomeCoordinatorOfStandaloneGroup") })
    }
}
