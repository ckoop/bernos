package de.bernos.sonos

/** Steuerbefehle für einen einzelnen Lautsprecher bzw. den Koordinator einer Gruppe. */
class SonosPlayerClient(private val soap: SoapClient) {

    suspend fun play(device: SonosDevice) {
        avTransport(device, "Play", "Speed" to "1")
    }

    suspend fun pause(device: SonosDevice) {
        try {
            avTransport(device, "Pause")
        } catch (e: SonosException) {
            // Manche Radiostreams lassen sich nicht pausieren, nur stoppen.
            if (e.upnpErrorCode == ERROR_TRANSITION_NOT_AVAILABLE) avTransport(device, "Stop") else throw e
        }
    }

    suspend fun next(device: SonosDevice) {
        avTransport(device, "Next")
    }

    suspend fun previous(device: SonosDevice) {
        avTransport(device, "Previous")
    }

    suspend fun transportState(device: SonosDevice): TransportState =
        TransportState.parse(avTransport(device, "GetTransportInfo")["CurrentTransportState"])

    data class PositionInfo(val track: TrackInfo?, val durationMs: Long?, val positionMs: Long?)

    suspend fun positionInfo(device: SonosDevice): PositionInfo {
        val result = avTransport(device, "GetPositionInfo")
        return PositionInfo(
            track = Parsers.parseTrackMetadata(result["TrackMetaData"], device.baseUrl),
            durationMs = Parsers.parseDuration(result["TrackDuration"]),
            positionMs = Parsers.parseDuration(result["RelTime"]),
        )
    }

    /** Titel der aktuellen Quelle, z. B. der Name eines Radiosenders. */
    suspend fun sourceTitle(device: SonosDevice): String? =
        Parsers.parseSourceTitle(avTransport(device, "GetMediaInfo")["CurrentURIMetaData"])

    /** Lautstärke der ganzen Gruppe (0–100). Muss am Koordinator abgefragt werden. */
    suspend fun groupVolume(coordinator: SonosDevice): Int? =
        groupRendering(coordinator, "GetGroupVolume")["CurrentVolume"]?.toIntOrNull()

    suspend fun setGroupVolume(coordinator: SonosDevice, volume: Int) {
        // Der Snapshot sorgt dafür, dass das Lautstärkeverhältnis zwischen den Räumen erhalten bleibt.
        groupRendering(coordinator, "SnapshotGroupVolume")
        groupRendering(coordinator, "SetGroupVolume", "DesiredVolume" to volume.coerceIn(0, 100).toString())
    }

    suspend fun groupMute(coordinator: SonosDevice): Boolean? =
        groupRendering(coordinator, "GetGroupMute")["CurrentMute"]?.let { it == "1" }

    suspend fun setGroupMute(coordinator: SonosDevice, muted: Boolean) {
        groupRendering(coordinator, "SetGroupMute", "DesiredMute" to if (muted) "1" else "0")
    }

    /** Lautstärke eines einzelnen Raums (0–100). */
    suspend fun volume(device: SonosDevice): Int? =
        rendering(device, "GetVolume", "Channel" to "Master")["CurrentVolume"]?.toIntOrNull()

    suspend fun setVolume(device: SonosDevice, volume: Int) {
        rendering(device, "SetVolume", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString())
    }

    /** Fügt [member] der Gruppe hinzu, deren Koordinator die UUID [coordinatorUuid] hat. */
    suspend fun joinGroup(member: SonosDevice, coordinatorUuid: String) {
        avTransport(member, "SetAVTransportURI", "CurrentURI" to "x-rincon:$coordinatorUuid", "CurrentURIMetaData" to "")
    }

    /** Löst [member] aus seiner Gruppe; er spielt danach allein (und zunächst nichts). */
    suspend fun leaveGroup(member: SonosDevice) {
        avTransport(member, "BecomeCoordinatorOfStandaloneGroup")
    }

    /**
     * Übergibt die Wiedergabe der Gruppe an das Mitglied [newCoordinatorUuid]. Mit
     * [rejoinGroup] = false verlässt der bisherige Koordinator danach die Gruppe.
     */
    suspend fun delegateCoordination(coordinator: SonosDevice, newCoordinatorUuid: String, rejoinGroup: Boolean) {
        avTransport(
            coordinator,
            "DelegateGroupCoordinationTo",
            "NewCoordinator" to newCoordinatorUuid,
            "RejoinGroup" to if (rejoinGroup) "1" else "0",
        )
    }

    /** Raumaufteilung des gesamten Sonos-Systems; jeder Lautsprecher kann sie liefern. */
    suspend fun zoneGroups(anyDevice: SonosDevice): List<ZoneGroup> {
        val state = soap.call(anyDevice, SonosService.ZONE_GROUP_TOPOLOGY, "GetZoneGroupState")["ZoneGroupState"]
            ?: throw SonosException("Keine Raumaufteilung von ${anyDevice.host} erhalten")
        return Parsers.parseZoneGroups(state)
    }

    private suspend fun avTransport(device: SonosDevice, action: String, vararg args: Pair<String, String>) =
        soap.call(device, SonosService.AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    private suspend fun groupRendering(device: SonosDevice, action: String, vararg args: Pair<String, String>) =
        soap.call(device, SonosService.GROUP_RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args)

    private suspend fun rendering(device: SonosDevice, action: String, vararg args: Pair<String, String>) =
        soap.call(device, SonosService.RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args)

    companion object {
        const val ERROR_TRANSITION_NOT_AVAILABLE = 701
    }
}
