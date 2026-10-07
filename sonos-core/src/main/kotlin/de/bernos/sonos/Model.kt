package de.bernos.sonos

/** Ein einzelner Sonos-Lautsprecher (bei Sonos "Zone Player"). */
data class SonosDevice(
    val uuid: String,
    val roomName: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
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

/** Die gewählte Quelle, z. B. ein Radiosender mit seinem Logo. */
data class SourceInfo(
    val title: String?,
    val albumArtUrl: String?,
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
