package de.bernos.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `Radio TuneIn - Stueck der Stream-URL ist kein Titel, Logo kommt aus der Quelle`() {
        // Echte Antwort eines Sonos-Lautsprechers mit TuneIn (STAR FM), gekürzt.
        val track = Parsers.parseTrackMetadata(
            """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
              xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
              <item id="-1" parentID="-1" restricted="true">
                <res protocolInfo="aac:*:application/octet-stream:*">aac://https://starfm.streamabc.net/30-simulcastberlin-aacplus-64-6777963?sABC=6np5qs90%230%23rp6pr4n3&amp;aw_0_1st.playerid=tunein</res>
                <dc:title>30-simulcastberlin-aacplus-64-6777963?sABC=6np5qs90#0#rp6pr4n3&amp;aw_0_1st.playerid=tunein</dc:title>
                <upnp:class>object.item</upnp:class>
              </item>
            </DIDL-Lite>
            """.trimIndent(),
            base,
        )
        assertNull(track)

        val source = Parsers.parseSourceInfo(
            """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
              xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
              <item id="-1" parentID="-1" restricted="true">
                <dc:title>STAR FM Maximum Rock Berlin</dc:title>
                <upnp:class>object.item.audioItem.audioBroadcast</upnp:class>
                <upnp:albumArtURI>https://sali.sonos.superhi.fi/image?w=60&amp;image=https%3A%2F%2Fcdn-profiles.tunein.com%2Fs8041%2Fimages%2Flogog.png&amp;partnerId=tunein</upnp:albumArtURI>
              </item>
            </DIDL-Lite>
            """.trimIndent(),
            base,
        )!!
        assertEquals("STAR FM Maximum Rock Berlin", source.title)
        assertEquals(
            "https://sali.sonos.superhi.fi/image?w=60&image=https%3A%2F%2Fcdn-profiles.tunein.com%2Fs8041%2Fimages%2Flogog.png&partnerId=tunein",
            source.albumArtUrl,
        )
    }

    @Test
    fun `Favoriten - Sender abspielbar, Verknuepfung nicht, Metadaten als Text`() {
        // Echte Antwort eines Sonos-Lautsprechers auf Browse("FV:2"), gekürzt.
        val didl = """
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">
            <item id="FV:2/0" parentID="FV:2" restricted="false"><dc:title>Aktuell angesagt</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class><r:ordinal>0</r:ordinal><res></res><r:type>shortcut</r:type><r:description>Sonos Radio</r:description><r:resMD>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot; xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;item id=&quot;10fe3064&quot;&gt;&lt;dc:title&gt;Aktuell angesagt&lt;/dc:title&gt;&lt;upnp:class&gt;object.container&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</r:resMD></item>
            <item id="FV:2/3" parentID="FV:2" restricted="false"><dc:title>STAR FM Maximum Rock Berlin</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class><r:ordinal>3</r:ordinal><res protocolInfo="x-sonosapi-stream:*:*:*">x-sonosapi-stream:tunein%3A5229?sid=303&amp;flags=8292&amp;sn=1</res><upnp:albumArtURI>https://sali.sonos.superhi.fi/image?w=60&amp;partnerId=tunein</upnp:albumArtURI><r:type>instantPlay</r:type><r:description>Sonos Radio</r:description><r:resMD>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot; xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;item id=&quot;10092064tunein%3A5229&quot;&gt;&lt;dc:title&gt;STAR FM Maximum Rock Berlin&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.audioItem.audioBroadcast&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</r:resMD></item>
            <item id="FV:2/4" parentID="FV:2" restricted="false"><dc:title>Lieblingslieder</dc:title><upnp:class>object.itemobject.item.sonos-favorite</upnp:class><res>x-rincon-cpcontainer:1006206cspotify%3Aplaylist%3A123</res><r:type>instantPlay</r:type><r:description>Spotify</r:description><r:resMD>&lt;DIDL-Lite xmlns:dc=&quot;http://purl.org/dc/elements/1.1/&quot; xmlns:upnp=&quot;urn:schemas-upnp-org:metadata-1-0/upnp/&quot; xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;item id=&quot;1006206c&quot;&gt;&lt;dc:title&gt;Lieblingslieder&lt;/dc:title&gt;&lt;upnp:class&gt;object.container.playlistContainer&lt;/upnp:class&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</r:resMD></item>
            </DIDL-Lite>
        """.trimIndent()

        val favorites = Parsers.parseFavorites(didl, base)

        assertEquals(listOf("Aktuell angesagt", "STAR FM Maximum Rock Berlin", "Lieblingslieder"), favorites.map { it.title })
        val (shortcut, radio, playlist) = favorites
        assertEquals(false, shortcut.isPlayable)
        assertEquals(true, radio.isPlayable)
        assertEquals(true, radio.isStream)
        assertEquals("x-sonosapi-stream:tunein%3A5229?sid=303&flags=8292&sn=1", radio.uri)
        assertEquals("https://sali.sonos.superhi.fi/image?w=60&partnerId=tunein", radio.albumArtUrl)
        assertEquals("FV:2/3", radio.id)
        assertTrue(radio.metadata!!.startsWith("<DIDL-Lite"))
        assertEquals(true, playlist.isPlayable)
        assertEquals(false, playlist.isStream)
        assertEquals("Spotify", playlist.description)
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
