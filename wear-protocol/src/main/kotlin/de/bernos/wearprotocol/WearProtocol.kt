package de.bernos.wearprotocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Gemeinsame Sprache von Handy und Uhr über die Wearable Data Layer API.
 *
 * Das Handy ist die Zentrale und spricht mit Sonos. Es veröffentlicht den Zustand als
 * DataItem unter [STATE_PATH] (mit dem Cover als Asset [COVER_ASSET]); die Uhr schickt
 * Befehle als Nachricht an [COMMAND_PATH].
 */
object WearProtocol {
    const val STATE_PATH = "/bernos/state"
    const val STATE_KEY = "state"
    const val COVER_ASSET = "cover"
    const val COMMAND_PATH = "/bernos/command"

    /** Fähigkeit, die die Handy-App anmeldet; die Uhr sucht darüber das richtige Gerät. */
    const val PHONE_CAPABILITY = "bernos_phone"

    /** Format-Version; bei inkompatiblen Änderungen erhöhen. */
    const val VERSION = 6
}

/** Ein Raum bzw. eine Gruppe, wie die Uhr sie in der Liste zeigt. */
data class WatchGroup(
    val id: String,
    val name: String,
    val isPlaying: Boolean = false,
    /** Was gerade läuft bzw. zuletzt lief, kurz ("Titel · Künstler"). */
    val nowPlaying: String? = null,
    /** Niedrigster Akkustand der Gruppe in Prozent; `null` ohne tragbaren Lautsprecher. */
    val batteryLevel: Int? = null,
    val charging: Boolean = false,
)

/** Ein einzelner Raum, in den sich die laufende Musik verschieben lässt. */
data class WatchRoom(
    val uuid: String,
    val name: String,
)

/** Per Bluetooth mit dem Handy verbundener Sonos-Kopfhörer (Ace) mit Akkustand in Prozent. */
data class WatchHeadphones(
    val name: String,
    val batteryLevel: Int,
)

/** Ein abspielbarer Sonos-Favorit. */
data class WatchFavorite(
    val id: String,
    val title: String,
)

/** Alles, was die Uhr anzeigt. Bewusst klein gehalten, die Position wird nicht übertragen. */
data class WatchState(
    val groups: List<WatchGroup> = emptyList(),
    val selectedGroupId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Nur zum Erkennen eines Coverwechsels; das Bild selbst kommt als Asset. */
    val coverUrl: String? = null,
    val isPlaying: Boolean = false,
    val volume: Int? = null,
    val muted: Boolean = false,
    val discovering: Boolean = false,
    val error: String? = null,
    /** Räume, in die sich die Musik der gewählten Gruppe verschieben lässt (ohne den steuernden Raum). */
    val moveTargets: List<WatchRoom> = emptyList(),
    /** Nur abspielbare Favoriten; Verknüpfungen gehen ohnehin nur in der Sonos-App. */
    val favorites: List<WatchFavorite> = emptyList(),
    /** Restminuten des Schlaftimers der gewählten Gruppe (aufgerundet); `null` = aus. */
    val sleepTimerMinutes: Int? = null,
    /** Nur solange der Kopfhörer mit dem Handy verbunden ist. */
    val headphones: WatchHeadphones? = null,
) {
    val selectedGroup: WatchGroup? get() = groups.firstOrNull { it.id == selectedGroupId }

    fun encode(): ByteArray = write { out ->
        out.writeInt(WearProtocol.VERSION)
        out.writeInt(groups.size)
        groups.forEach {
            out.writeUTF(it.id)
            out.writeUTF(it.name)
            out.writeBoolean(it.isPlaying)
            out.writeNullable(it.nowPlaying)
            out.writeInt(it.batteryLevel ?: -1)
            out.writeBoolean(it.charging)
        }
        out.writeNullable(selectedGroupId)
        out.writeNullable(title)
        out.writeNullable(artist)
        out.writeNullable(album)
        out.writeNullable(coverUrl)
        out.writeBoolean(isPlaying)
        out.writeInt(volume ?: -1)
        out.writeBoolean(muted)
        out.writeBoolean(discovering)
        out.writeNullable(error)
        out.writeInt(moveTargets.size)
        moveTargets.forEach {
            out.writeUTF(it.uuid)
            out.writeUTF(it.name)
        }
        out.writeInt(favorites.size)
        favorites.forEach {
            out.writeUTF(it.id)
            out.writeUTF(it.title)
        }
        out.writeInt(sleepTimerMinutes ?: -1)
        out.writeBoolean(headphones != null)
        headphones?.let {
            out.writeUTF(it.name)
            out.writeInt(it.batteryLevel)
        }
    }

    companion object {
        /** Liest einen Zustand; `null` bei fremdem oder kaputtem Format. */
        fun decode(bytes: ByteArray): WatchState? = read(bytes) { input ->
            if (input.readInt() != WearProtocol.VERSION) return@read null
            val groups = List(input.readInt()) {
                WatchGroup(
                    id = input.readUTF(),
                    name = input.readUTF(),
                    isPlaying = input.readBoolean(),
                    nowPlaying = input.readNullable(),
                    batteryLevel = input.readInt().takeIf { it >= 0 },
                    charging = input.readBoolean(),
                )
            }
            WatchState(
                groups = groups,
                selectedGroupId = input.readNullable(),
                title = input.readNullable(),
                artist = input.readNullable(),
                album = input.readNullable(),
                coverUrl = input.readNullable(),
                isPlaying = input.readBoolean(),
                volume = input.readInt().takeIf { it >= 0 },
                muted = input.readBoolean(),
                discovering = input.readBoolean(),
                error = input.readNullable(),
                moveTargets = List(input.readInt()) { WatchRoom(uuid = input.readUTF(), name = input.readUTF()) },
                favorites = List(input.readInt()) { WatchFavorite(id = input.readUTF(), title = input.readUTF()) },
                sleepTimerMinutes = input.readInt().takeIf { it >= 0 },
                headphones = if (input.readBoolean()) WatchHeadphones(name = input.readUTF(), batteryLevel = input.readInt()) else null,
            )
        }
    }
}

/** Befehle der Uhr an das Handy. */
sealed interface WatchCommand {
    /** Uhr-App ist sichtbar: Handy soll suchen bzw. aktualisieren und den Zustand senden. */
    data object Hello : WatchCommand
    data object Refresh : WatchCommand
    data class SelectGroup(val groupId: String) : WatchCommand
    data object PlayPause : WatchCommand
    data object Next : WatchCommand
    data object Previous : WatchCommand
    data class SetVolume(val volume: Int) : WatchCommand
    data class SetMuted(val muted: Boolean) : WatchCommand
    /** Schlaftimer der gewählten Gruppe; 0 schaltet ihn aus. */
    data class SetSleepTimer(val minutes: Int) : WatchCommand
    /** Laufende Musik der gewählten Gruppe in diesen Raum verschieben; der bisherige Raum verstummt. */
    data class MoveTo(val roomUuid: String) : WatchCommand
    /** Favoriten in der gewählten Gruppe abspielen. */
    data class PlayFavorite(val favoriteId: String) : WatchCommand

    fun encode(): ByteArray = write { out ->
        out.writeInt(WearProtocol.VERSION)
        when (this) {
            Hello -> out.writeUTF("hello")
            Refresh -> out.writeUTF("refresh")
            is SelectGroup -> {
                out.writeUTF("select")
                out.writeUTF(groupId)
            }
            PlayPause -> out.writeUTF("playPause")
            Next -> out.writeUTF("next")
            Previous -> out.writeUTF("previous")
            is SetVolume -> {
                out.writeUTF("volume")
                out.writeInt(volume)
            }
            is SetMuted -> {
                out.writeUTF("mute")
                out.writeBoolean(muted)
            }
            is SetSleepTimer -> {
                out.writeUTF("sleep")
                out.writeInt(minutes)
            }
            is MoveTo -> {
                out.writeUTF("move")
                out.writeUTF(roomUuid)
            }
            is PlayFavorite -> {
                out.writeUTF("favorite")
                out.writeUTF(favoriteId)
            }
        }
    }

    companion object {
        /** Liest einen Befehl; `null` bei unbekanntem Befehl oder fremdem Format. */
        fun decode(bytes: ByteArray): WatchCommand? = read(bytes) { input ->
            if (input.readInt() != WearProtocol.VERSION) return@read null
            when (input.readUTF()) {
                "hello" -> Hello
                "refresh" -> Refresh
                "select" -> SelectGroup(input.readUTF())
                "playPause" -> PlayPause
                "next" -> Next
                "previous" -> Previous
                "volume" -> SetVolume(input.readInt().coerceIn(0, 100))
                "mute" -> SetMuted(input.readBoolean())
                "sleep" -> SetSleepTimer(input.readInt().coerceIn(0, 24 * 60))
                "move" -> MoveTo(input.readUTF())
                "favorite" -> PlayFavorite(input.readUTF())
                else -> null
            }
        }
    }
}

private inline fun write(block: (DataOutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use(block)
    return bytes.toByteArray()
}

private inline fun <T> read(bytes: ByteArray, block: (DataInputStream) -> T?): T? =
    runCatching { DataInputStream(ByteArrayInputStream(bytes)).use(block) }.getOrNull()

private fun DataOutputStream.writeNullable(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeUTF(value)
}

private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null
