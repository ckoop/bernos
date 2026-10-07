package de.bernos.app.wear

import de.bernos.sonos.SonosState
import de.bernos.wearprotocol.WatchFavorite
import de.bernos.wearprotocol.WatchGroup
import de.bernos.wearprotocol.WatchRoom
import de.bernos.wearprotocol.WatchState

/** Verdichtet den Zustand des Controllers auf das, was die Uhr anzeigt. */
internal fun SonosState.toWatchState(): WatchState {
    val selectedPlaying = nowPlaying?.takeIf { it.groupId == selectedGroupId }
    return WatchState(
        groups = groups
            .sortedBy { it.name.lowercase() }
            .map { group ->
                // Für die gewählte Gruppe ist die laufende Abfrage am frischesten.
                val playback = groupPlayback[group.id]
                val playing = if (group.id == selectedGroupId && selectedPlaying != null) selectedPlaying.isPlaying else playback?.isPlaying == true
                val track = playback?.track
                val battery = group.members.mapNotNull { batteries[it.uuid] }.minByOrNull { it.level }
                WatchGroup(
                    id = group.id,
                    name = group.name,
                    isPlaying = playing,
                    nowPlaying = listOfNotNull(track?.title ?: track?.album, track?.artist).joinToString(" · ").ifEmpty { null },
                    batteryLevel = battery?.level,
                    charging = battery?.charging == true,
                )
            },
        selectedGroupId = selectedGroupId,
        title = selectedPlaying?.track?.title,
        artist = selectedPlaying?.track?.artist,
        album = selectedPlaying?.track?.album,
        coverUrl = selectedPlaying?.track?.albumArtUrl,
        isPlaying = selectedPlaying?.isPlaying == true,
        volume = selectedPlaying?.volume,
        muted = selectedPlaying?.muted == true,
        discovering = discovering,
        error = error,
        moveTargets = selectedGroup?.let { group ->
            rooms.filter { it.uuid != group.coordinator.uuid }.map { WatchRoom(uuid = it.uuid, name = it.roomName) }
        } ?: emptyList(),
        favorites = favorites.filter { it.isPlayable }.map { WatchFavorite(id = it.id, title = it.title) },
        sleepTimerMinutes = selectedPlaying?.sleepTimerRemainingMs?.let { ((it + 59_999) / 60_000).toInt() },
    )
}
