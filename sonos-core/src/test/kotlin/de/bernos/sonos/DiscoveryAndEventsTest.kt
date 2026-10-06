package de.bernos.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class DiscoveryAndEventsTest {

    @Test
    fun `SSDP-Antwort eines Sonos-Geräts liefert den Host`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age = 1800\r\n" +
            "LOCATION: http://192.168.1.20:1400/xml/device_description.xml\r\n" +
            "SERVER: Linux UPnP/1.0 Sonos/80.1-55014 (ZPS37)\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        assertEquals("192.168.1.20", SsdpDiscovery.parseResponse(response))
    }

    @Test
    fun `SSDP-Antworten anderer Geräte werden ignoriert`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://192.168.1.1:5000/rootDesc.xml\r\n" +
            "SERVER: Router UPnP/1.1 MiniUPnPd/2.0\r\n" +
            "ST: upnp:rootdevice\r\n\r\n"
        assertNull(SsdpDiscovery.parseResponse(response))
    }

    @Test
    fun `Ereignis-Server nimmt NOTIFY entgegen`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = GenaEventServer(scope)
        server.start()
        try {
            val received = async { withTimeout(5000) { server.events.first() } }
            val body = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"/>"
            var reply = ""
            // Kurz warten, bis der Sammler aktiv ist, dann senden.
            kotlinx.coroutines.delay(100)
            Socket("127.0.0.1", server.port).use { socket ->
                socket.getOutputStream().write(
                    ("NOTIFY /bernos HTTP/1.1\r\nHOST: 127.0.0.1\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                        "SID: uuid:RINCON_A_sub0000000001\r\nSEQ: 0\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
                        .toByteArray(),
                )
                reply = socket.getInputStream().bufferedReader().readLine()
            }
            val event = received.await()
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            assertEquals("uuid:RINCON_A_sub0000000001", event.sid)
            assertEquals(body, event.body)
        } finally {
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `Position wird beim Abspielen hochgerechnet`() {
        val np = NowPlaying("g", TransportState.PLAYING, null, 10_000, 4_000, 1_000, null, null)
        assertEquals(6_000L, np.estimatedPositionMs(3_000))
        assertEquals(10_000L, np.estimatedPositionMs(60_000))
        assertEquals(4_000L, np.copy(transportState = TransportState.PAUSED).estimatedPositionMs(3_000))
    }
}
