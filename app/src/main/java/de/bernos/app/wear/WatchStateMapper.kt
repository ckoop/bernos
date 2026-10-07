package de.bernos.app.wear

import de.bernos.sonos.SonosState
import de.bernos.wearprotocol.WatchGroup
import de.bernos.wearprotocol.WatchRoom
import de.bernos.wearprotocol.WatchState

/** Verdichtet den Zustand des Controllers auf das, was die Uhr anzeigt. */
internal fun SonosState.toWatchState(): WatchState {
    val selectedPlaying = nowPlaying?.takeIf { it.groupId == selectedGroupId }
    return WatchState(
        groups = groups
            .sortedBy { it.name.lowercase() }
            .map { WatchGroup(id = it.id, name = it.name, isPlaying = it.id == selectedGroupId && selectedPlaying?.isPlaying == true) },
        selectedGroupId = selectedGroupId,
        title = selectedPlaying?.track?.title,
        artist = selectedPlaying?.track?.artist,
        album = selectedPlaying?.track?.album,
        coverUrl = selectedPlaying?.track?.albumArtUrl,
        isPlaying = selectedPlaying?.isPlaying == true,
        volume = selectedPlaying?.volume,
        discovering = discovering,
        error = error,
        moveTargets = selectedGroup?.let { group ->
            rooms.filter { it.uuid != group.coordinator.uuid }.map { WatchRoom(uuid = it.uuid, name = it.roomName) }
        } ?: emptyList(),
    )
}
