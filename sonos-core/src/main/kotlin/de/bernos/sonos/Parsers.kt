package de.bernos.sonos

import java.net.URI

internal object Parsers {

    /**
     * Liest Titel, Künstler, Album und Cover aus DIDL-Lite-Metadaten, wie sie
     * `GetPositionInfo` im Feld `TrackMetaData` liefert.
     */
    fun parseTrackMetadata(didl: String?, baseUrl: String): TrackInfo? {
        if (didl.isNullOrBlank() || didl == "NOT_IMPLEMENTED") return null
        val doc = runCatching { Xml.parse(didl) }.getOrNull() ?: return null

        var title = doc.firstText("title")
        var artist = doc.firstText("creator") ?: doc.firstText("albumArtist")
        val album = doc.firstText("album")
        val art = doc.firstText("albumArtURI")?.let { resolveUrl(it, baseUrl) }

        // Bei Radiosendern steht der laufende Titel meist als "Künstler - Titel" in streamContent.
        val stream = doc.firstText("streamContent")
        if (stream != null && !stream.startsWith("ZPSTR_")) {
            val parts = stream.split(" - ", limit = 2)
            if (parts.size == 2) {
                artist = parts[0].trim()
                title = parts[1].trim()
            } else {
                title = stream
            }
        }
        // Manche Quellen liefern als Titel nur die Stream-URL.
        if (title != null && title.contains("://")) title = null

        if (title == null && artist == null && album == null && art == null) return null
        return TrackInfo(title = title, artist = artist, album = album, albumArtUrl = art)
    }

    /** Name des Senders bzw. der Quelle aus `GetMediaInfo` → `CurrentURIMetaData`. */
    fun parseSourceTitle(didl: String?): String? {
        if (didl.isNullOrBlank() || didl == "NOT_IMPLEMENTED") return null
        return runCatching { Xml.parse(didl).firstText("title") }.getOrNull()
    }

    fun resolveUrl(url: String, baseUrl: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> baseUrl + url
        else -> "$baseUrl/$url"
    }

    /** "H:MM:SS" → Millisekunden; `null` wenn unbekannt (z. B. bei Streams). */
    fun parseDuration(value: String?): Long? {
        if (value.isNullOrBlank() || value == "NOT_IMPLEMENTED") return null
        val parts = value.split(":")
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].substringBefore('.').toLongOrNull() ?: return null
        return ((h * 60 + m) * 60 + s) * 1000
    }

    /**
     * Wertet den von `GetZoneGroupState` gelieferten `ZoneGroupState` aus.
     * Unsichtbare Mitglieder (Surround-Lautsprecher, Sub, gekoppelte Stereo-Partner)
     * und Bridges/Boosts werden ausgelassen.
     */
    fun parseZoneGroups(zoneGroupState: String): List<ZoneGroup> {
        val doc = Xml.parse(zoneGroupState)
        return doc.descendants("ZoneGroup").mapNotNull { group ->
            val coordinatorUuid = group.getAttribute("Coordinator")
            val members = group.childElements()
                .filter { it.localName == "ZoneGroupMember" }
                .filter { it.getAttribute("Invisible") != "1" && it.getAttribute("IsZoneBridge") != "1" }
                .mapNotNull { member ->
                    val location = runCatching { URI(member.getAttribute("Location")) }.getOrNull()
                    val host = location?.host ?: return@mapNotNull null
                    SonosDevice(
                        uuid = member.getAttribute("UUID"),
                        roomName = member.getAttribute("ZoneName"),
                        host = host,
                        port = location.port.takeIf { it > 0 } ?: SonosDevice.DEFAULT_PORT,
                    )
                }
            val coordinator = members.firstOrNull { it.uuid == coordinatorUuid } ?: return@mapNotNull null
            ZoneGroup(
                id = group.getAttribute("ID").ifEmpty { coordinatorUuid },
                coordinator = coordinator,
                members = listOf(coordinator) + members.filter { it.uuid != coordinatorUuid }.sortedBy { it.roomName },
            )
        }.sortedBy { it.name.lowercase() }
    }
}
