package de.bernos.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsersTest {
    private val base = "http://192.168.1.20:1400"

    @Test
    fun `liest Titel, Künstler, Album und relatives Cover`() {
        val didl = """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
              xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
              <item id="-1" parentID="-1">
                <res duration="0:03:45">x-sonos-spotify:spotify%3atrack%3a123</res>
                <upnp:albumArtURI>/getaa?s=1&amp;u=x-sonos-spotify%3aspotify</upnp:albumArtURI>
                <dc:title>Bohemian Rhapsody</dc:title>
                <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                <dc:creator>Queen</dc:creator>
                <upnp:album>A Night at the Opera</upnp:album>
              </item>
            </DIDL-Lite>
        """.trimIndent()

        val track = Parsers.parseTrackMetadata(didl, base)!!

        assertEquals("Bohemian Rhapsody", track.title)
        assertEquals("Queen", track.artist)
        assertEquals("A Night at the Opera", track.album)
        assertEquals("$base/getaa?s=1&u=x-sonos-spotify%3aspotify", track.albumArtUrl)
    }

    @Test
    fun `Radio - Titel kommt aus streamContent`() {
        val didl = """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
              xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
              <item id="-1" parentID="-1">
                <dc:title>x-sonosapi-stream:s1234?sid=254</dc:title>
                <r:streamContent>Daft Punk - Get Lucky</r:streamContent>
                <upnp:albumArtURI>https://cdn.example.org/logo.png</upnp:albumArtURI>
              </item>
            </DIDL-Lite>
        """.trimIndent()

        val track = Parsers.parseTrackMetadata(didl, base)!!

        assertEquals("Get Lucky", track.title)
        assertEquals("Daft Punk", track.artist)
        assertEquals("https://cdn.example.org/logo.png", track.albumArtUrl)
    }

    @Test
    fun `leere Metadaten ergeben null`() {
        assertNull(Parsers.parseTrackMetadata("", base))
        assertNull(Parsers.parseTrackMetadata("NOT_IMPLEMENTED", base))
        assertNull(Parsers.parseTrackMetadata("kein xml", base))
    }

    @Test
    fun `Dauer wird in Millisekunden umgerechnet`() {
        assertEquals(225_000L, Parsers.parseDuration("0:03:45"))
        assertEquals(3_723_000L, Parsers.parseDuration("1:02:03"))
        assertNull(Parsers.parseDuration("NOT_IMPLEMENTED"))
        assertNull(Parsers.parseDuration(""))
    }

    @Test
    fun `Raumaufteilung ohne unsichtbare Lautsprecher und Bridges`() {
        val state = """
            <ZoneGroupState><ZoneGroups>
              <ZoneGroup Coordinator="RINCON_A" ID="RINCON_A:1">
                <ZoneGroupMember UUID="RINCON_A" Location="http://192.168.1.20:1400/xml/device_description.xml" ZoneName="Wohnzimmer">
                  <Satellite UUID="RINCON_SUB" Location="http://192.168.1.29:1400/xml/device_description.xml" ZoneName="Wohnzimmer" Invisible="1"/>
                </ZoneGroupMember>
                <ZoneGroupMember UUID="RINCON_B" Location="http://192.168.1.21:1400/xml/device_description.xml" ZoneName="Küche"/>
                <ZoneGroupMember UUID="RINCON_C" Location="http://192.168.1.22:1400/xml/device_description.xml" ZoneName="Küche" Invisible="1"/>
              </ZoneGroup>
              <ZoneGroup Coordinator="RINCON_D" ID="RINCON_D:7">
                <ZoneGroupMember UUID="RINCON_D" Location="http://192.168.1.23:1400/xml/device_description.xml" ZoneName="Bad"/>
              </ZoneGroup>
              <ZoneGroup Coordinator="RINCON_E" ID="RINCON_E:2">
                <ZoneGroupMember UUID="RINCON_E" Location="http://192.168.1.24:1400/xml/device_description.xml" ZoneName="BOOST" IsZoneBridge="1"/>
              </ZoneGroup>
            </ZoneGroups></ZoneGroupState>
        """.trimIndent()

        val groups = Parsers.parseZoneGroups(state)

        assertEquals(listOf("Bad", "Wohnzimmer + Küche"), groups.map { it.name })
        val wohnzimmer = groups[1]
        assertEquals("RINCON_A:1", wohnzimmer.id)
        assertEquals("192.168.1.20", wohnzimmer.coordinator.host)
        assertEquals(listOf("RINCON_A", "RINCON_B"), wohnzimmer.members.map { it.uuid })
    }
}
