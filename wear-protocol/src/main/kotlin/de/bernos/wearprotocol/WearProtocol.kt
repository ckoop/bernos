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
    const val VERSION = 1
}

/** Ein Raum bzw. eine Gruppe, wie die Uhr sie in der Liste zeigt. */
data class WatchGroup(
    val id: String,
    val name: String,
    val isPlaying: Boolean = false,
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
    val discovering: Boolean = false,
    val error: String? = null,
) {
    val selectedGroup: WatchGroup? get() = groups.firstOrNull { it.id == selectedGroupId }

    fun encode(): ByteArray = write { out ->
        out.writeInt(WearProtocol.VERSION)
        out.writeInt(groups.size)
        groups.forEach {
            out.writeUTF(it.id)
            out.writeUTF(it.name)
            out.writeBoolean(it.isPlaying)
        }
        out.writeNullable(selectedGroupId)
        out.writeNullable(title)
        out.writeNullable(artist)
        out.writeNullable(album)
        out.writeNullable(coverUrl)
        out.writeBoolean(isPlaying)
        out.writeInt(volume ?: -1)
        out.writeBoolean(discovering)
        out.writeNullable(error)
    }

    companion object {
        /** Liest einen Zustand; `null` bei fremdem oder kaputtem Format. */
        fun decode(bytes: ByteArray): WatchState? = read(bytes) { input ->
            if (input.readInt() != WearProtocol.VERSION) return@read null
            val groups = List(input.readInt()) {
                WatchGroup(id = input.readUTF(), name = input.readUTF(), isPlaying = input.readBoolean())
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
                discovering = input.readBoolean(),
                error = input.readNullable(),
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
