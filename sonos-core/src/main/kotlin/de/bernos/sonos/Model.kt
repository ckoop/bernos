package de.bernos.sonos

/** Ein einzelner Sonos-Lautsprecher (bei Sonos "Zone Player"). */
data class SonosDevice(
    val uuid: String,
    val roomName: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** Soundbar am Fernseher (Arc, Beam, Ray …): hat einen TV-Eingang, Nachtmodus und Sprachverbesserung. */
    val isHomeTheater: Boolean = false,
) {
    val baseUrl: String get() = "http://$host:$port"

    companion object {
        const val DEFAULT_PORT = 1400
    }
}

/**
 * Eine Gruppe von Lautsprechern, die gemeinsam abspielen. Ein einzelner Raum ist
 * eine Gruppe mit nur einem Mitglied. Steuerbefehle gehen immer an den [coordinator].
 */
data class ZoneGroup(
    val id: String,
    val coordinator: SonosDevice,
    val members: List<SonosDevice>,
) {
    val name: String
        get() {
            val others = members.filter { it.uuid != coordinator.uuid }.map { it.roomName }
            return if (others.isEmpty()) coordinator.roomName else "${coordinator.roomName} + ${others.joinToString(" + ")}"
        }

    /** Die Soundbar der Gruppe, falls eine dabei ist. */
    val homeTheater: SonosDevice? get() = members.firstOrNull { it.isHomeTheater }
}

enum class TransportState {
    PLAYING,
    PAUSED,
    STOPPED,
    TRANSITIONING,
    UNKNOWN;

    companion object {
        fun parse(value: String?): TransportState = when (value) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK" -> PAUSED
            "STOPPED" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            else -> UNKNOWN
        }
    }
}

/** Metadaten des aktuellen Titels. Alle Felder können fehlen, z. B. bei Line-In oder TV. */
data class TrackInfo(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtUrl: String?,
)

/**
 * Ein Sonos-Favorit ("Meine Sonos"). Verknüpfungen (z. B. Bereiche von Sonos Radio) haben keine
 * [uri] und lassen sich nur in der Sonos-App öffnen.
 */
data class Favorite(
    val id: String,
    val title: String,
    val uri: String?,
    /** DIDL-Lite-Metadaten, die Sonos beim Abspielen zusammen mit der [uri] braucht. */
    val metadata: String?,
    val albumArtUrl: String?,
    /** Quelle laut Sonos, z. B. "TuneIn" oder "Spotify". */
    val description: String?,
) {
    val isPlayable: Boolean get() = !uri.isNullOrBlank()

    /** Radiosender laufen direkt; Playlists, Alben und Titel gehen über die Warteschlange. */
    val isStream: Boolean
        get() = uri != null && (STREAM_SCHEMES.any { uri.startsWith(it) } || metadata?.contains("audioBroadcast") == true)

    private companion object {
        val STREAM_SCHEMES = listOf("x-sonosapi-stream:", "x-sonosapi-radio:", "x-rincon-mp3radio:", "x-sonosapi-hls:", "aac:", "hls-radio:")
    }
}

/** Kurzer Wiedergabestand einer Gruppe für Übersichten (Raumliste). */
data class GroupPlayback(
    val transportState: TransportState,
    val track: TrackInfo?,
) {
    val isPlaying: Boolean get() = transportState == TransportState.PLAYING
}

/** Akkustand eines tragbaren Lautsprechers (z. B. Sonos Roam oder Move). */
data class BatteryStatus(
    /** 0–100 Prozent. */
    val level: Int,
    /** Hängt am Strom bzw. steht auf der Ladeschale. */
    val charging: Boolean,
)

/** Die gewählte Quelle, z. B. ein Radiosender mit seinem Logo. */
data class SourceInfo(
    val title: String?,
    val albumArtUrl: String?,
)

/** Fernseh-Funktionen der Soundbar einer Gruppe. */
data class HomeTheaterState(
    /** UUID der Soundbar. */
    val deviceUuid: String,
    /** Die Gruppe spielt gerade den Ton des Fernsehers. */
    val tvActive: Boolean,
    /** `null`, wenn unbekannt (Abfrage fehlgeschlagen). */
    val nightMode: Boolean?,
    val speechEnhancement: Boolean?,
)

/** Momentaufnahme dessen, was eine Gruppe gerade abspielt. */
data class NowPlaying(
    val groupId: String,
    val transportState: TransportState,
    val track: TrackInfo?,
    val durationMs: Long?,
    val positionMs: Long?,
    /** Zeitpunkt (ms, Uhr des Controllers), zu dem [positionMs] gemessen wurde. */
    val positionCapturedAtMs: Long,
    val volume: Int?,
    val muted: Boolean?,
    /** Lautstärke der einzelnen Räume der Gruppe, nach UUID. */
    val memberVolumes: Map<String, Int> = emptyMap(),
    /** Restzeit des Schlaftimers; `null`, wenn keiner läuft. */
    val sleepTimerRemainingMs: Long? = null,
    /** Nur bei Gruppen mit Soundbar. */
    val homeTheater: HomeTheaterState? = null,
) {
    val isPlaying: Boolean get() = transportState == TransportState.PLAYING

    /** Aktuelle Position, hochgerechnet seit der letzten Messung, solange abgespielt wird. */
    fun estimatedPositionMs(nowMs: Long): Long? {
        val position = positionMs ?: return null
        if (!isPlaying) return position
        val estimated = position + (nowMs - positionCapturedAtMs).coerceAtLeast(0)
        return durationMs?.let { estimated.coerceAtMost(it) } ?: estimated
    }
}

class SonosException(message: String, val upnpErrorCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)
