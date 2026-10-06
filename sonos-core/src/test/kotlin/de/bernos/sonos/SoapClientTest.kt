package de.bernos.sonos

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SoapClientTest {
    private val server = MockWebServer()
    private lateinit var device: SonosDevice
    private val client = SoapClient(OkHttpClient())

    @Before
    fun setUp() {
        server.start()
        device = SonosDevice("RINCON_A", "Wohnzimmer", server.hostName, server.port)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `sendet SOAP-Anfrage und liest die Antwort`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                   <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                   <CurrentTransportState>PLAYING</CurrentTransportState><CurrentSpeed>1</CurrentSpeed>
                   </u:GetTransportInfoResponse></s:Body></s:Envelope>""",
            ),
        )

        val result = client.call(device, SonosService.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))

        assertEquals("PLAYING", result["CurrentTransportState"])
        val request = server.takeRequest()
        assertEquals("/MediaRenderer/AVTransport/Control", request.path)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"", request.getHeader("SOAPACTION"))
        assertTrue(request.body.readUtf8().contains("<InstanceID>0</InstanceID>"))
    }

    @Test
    fun `UPnP-Fehlercode wird weitergegeben`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody(
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
                   <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
                   <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode></UPnPError></detail>
                   </s:Fault></s:Body></s:Envelope>""",
            ),
        )

        try {
            client.call(device, SonosService.AV_TRANSPORT, "Next", listOf("InstanceID" to "0"))
            fail("SonosException erwartet")
        } catch (e: SonosException) {
            assertEquals(701, e.upnpErrorCode)
        }
    }

    @Test
    fun `Argumente werden XML-maskiert`() {
        val body = SoapClient.envelope(SonosService.AV_TRANSPORT, "X", listOf("A" to "<b>&"))
        assertTrue(body.contains("<A>&lt;b&gt;&amp;</A>"))
    }
}
