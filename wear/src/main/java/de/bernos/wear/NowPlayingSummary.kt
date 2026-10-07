package de.bernos.wear

import de.bernos.wearprotocol.WatchState

/**
 * Das Wichtigste für Kachel und Komplikation in Kurzform. Gleiche Regeln wie die
 * Wiedergabeansicht: Bei Radio-Werbung ohne Titel steht der Sendername als Titel da.
 */
data class NowPlayingSummary(
    /** `false`, solange das Handy keinen Zustand geliefert hat. */
    val connected: Boolean,
    val room: String?,
    val title: String?,
    val subtitle: String?,
    val isPlaying: Boolean,
) {
    /** "Titel – Künstler" für lange Texte. */
    val longText: String? get() = listOfNotNull(title, subtitle).joinToString(" – ").ifEmpty { null }

    companion object {
        fun from(state: WatchState?): NowPlayingSummary {
            if (state == null) return NowPlayingSummary(connected = false, room = null, title = null, subtitle = null, isPlaying = false)
            val room = state.selectedGroup?.name
                ?: return NowPlayingSummary(connected = true, room = null, title = null, subtitle = null, isPlaying = false)
            return NowPlayingSummary(
                connected = true,
                room = room,
                title = state.title ?: state.album,
                subtitle = state.artist ?: state.album.takeIf { state.title != null },
                isPlaying = state.isPlaying,
            )
        }
    }
}
