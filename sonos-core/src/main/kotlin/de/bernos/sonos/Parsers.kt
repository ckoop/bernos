package de.bernos.sonos

import java.net.URI
import java.net.URLDecoder

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
        // Manche Quellen liefern als Titel nur die Stream-URL oder ein Stück davon
        // (TuneIn z. B. "30-simulcastberlin-aacplus-64-...?sABC=...").
        if (title != null && (title.contains("://") || isPartOfStreamUrl(title, doc.firstText("res")))) title = null

        if (title == null && artist == null && album == null && art == null) return null
        return TrackInfo(title = title, artist = artist, album = album, albumArtUrl = art)
    }

    private fun isPartOfStreamUrl(title: String, res: String?): Boolean {
        if (res == null || title.length < 8) return false
        val decoded = runCatching { URLDecoder.decode(res, Charsets.UTF_8) }.getOrDefault(res)
        return res.contains(title) || decoded.contains(title)
    }

    /**
     * Name und Bild des Senders bzw. der Quelle aus `GetMediaInfo` → `CurrentURIMetaData`.
     * Bei Radiosendern (z. B. TuneIn) steht das Senderlogo nur hier, nicht in `TrackMetaData`.
     */
    fun parseSourceInfo(didl: String?, baseUrl: String): SourceInfo? {
        if (didl.isNullOrBlank() || didl == "NOT_IMPLEMENTED") return null
        val doc = runCatching { Xml.parse(didl) }.getOrNull() ?: return null
        val title = doc.firstText("title")
        val art = doc.firstText("albumArtURI")?.let { resolveUrl(it, baseUrl) }
        if (title == null && art == null) return null
        return SourceInfo(title = title, albumArtUrl = art)
    }

    /**
     * Favoriten aus `Browse("FV:2")`. Die Metadaten zum Abspielen stehen als maskiertes
     * DIDL-Lite in `r:resMD` und kommen hier als Text heraus.
     */
    fun parseFavorites(didl: String?, baseUrl: String): List<Favorite> {
        if (didl.isNullOrBlank()) return emptyList()
        val doc = runCatching { Xml.parse(didl) }.getOrNull() ?: return emptyList()
        return doc.descendants("item").mapNotNull { item ->
            val title = item.firstText("title") ?: return@mapNotNull null
            Favorite(
                id = item.getAttribute("id"),
                title = title,
                uri = item.firstText("res"),
                metadata = item.firstText("resMD"),
                albumArtUrl = item.firstText("albumArtURI")?.let { resolveUrl(it, baseUrl) },
                description = item.firstText("description"),
            )
        }
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
