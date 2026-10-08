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

    /** Aktuelle Quelle, z. B. Name und Logo eines Radiosenders. */
    suspend fun sourceInfo(device: SonosDevice): SourceInfo? =
        Parsers.parseSourceInfo(avTransport(device, "GetMediaInfo")["CurrentURIMetaData"], device.baseUrl)

    /** Adresse der gewählten Quelle, z. B. `x-sonos-htastream:…` beim Fernseher. */
    suspend fun currentUri(device: SonosDevice): String? = avTransport(device, "GetMediaInfo")["CurrentURI"]

    /** Schaltet die Soundbar auf den Fernseher; laufende Musik der Gruppe endet dabei. */
    suspend fun switchToTv(soundbar: SonosDevice) {
        avTransport(soundbar, "SetAVTransportURI", "CurrentURI" to "$TV_URI_PREFIX${soundbar.uuid}:spdif", "CurrentURIMetaData" to "")
        // Der TV-Ton läuft meist von selbst an; manche Fernseher brauchen den Anstoß.
        runCatching { play(soundbar) }
    }

    /** Klangeinstellung einer Soundbar, z. B. [EQ_NIGHT_MODE]; `null`, wenn sie fehlt. */
    suspend fun eq(device: SonosDevice, type: String): Int? =
        rendering(device, "GetEQ", "EQType" to type)["CurrentValue"]?.toIntOrNull()

    suspend fun setEq(device: SonosDevice, type: String, value: Int) {
        rendering(device, "SetEQ", "EQType" to type, "DesiredValue" to value.toString())
    }

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

    /** Restzeit des Schlaftimers der Gruppe; `null`, wenn keiner läuft. */
    suspend fun sleepTimerRemaining(coordinator: SonosDevice): Long? =
        Parsers.parseDuration(avTransport(coordinator, "GetRemainingSleepTimerDuration")["RemainingSleepTimerDuration"])
            ?.takeIf { it > 0 }

    /** Schlaftimer stellen; [minutes] = `null` oder 0 schaltet ihn aus. Sonos stoppt dann von selbst. */
    suspend fun setSleepTimer(coordinator: SonosDevice, minutes: Int?) {
        val duration = minutes?.takeIf { it > 0 }?.let { "%02d:%02d:00".format(it / 60, it % 60) } ?: ""
        avTransport(coordinator, "ConfigureSleepTimer", "NewSleepTimerDuration" to duration)
    }

    /** Akkustand; `null` bei Lautsprechern ohne Akku. */
    suspend fun battery(device: SonosDevice): BatteryStatus? = Parsers.parseBattery(soap.get(device, "/status/batterystatus"))

    /** Sonos-Favoriten des ganzen Systems; jeder Lautsprecher kann sie liefern. */
    suspend fun favorites(anyDevice: SonosDevice): List<Favorite> {
        val result = soap.call(
            anyDevice,
            SonosService.CONTENT_DIRECTORY,
            "Browse",
            listOf(
                "ObjectID" to "FV:2",
                "BrowseFlag" to "BrowseDirectChildren",
                "Filter" to "*",
                "StartingIndex" to "0",
                "RequestedCount" to "200",
                "SortCriteria" to "",
            ),
        )
        return Parsers.parseFavorites(result["Result"], anyDevice.baseUrl)
    }

    /**
     * Spielt einen Favoriten in der Gruppe von [coordinator] ab. Radiosender werden direkt
     * gesetzt; alles andere ersetzt die Warteschlange und spielt sie von vorn, wie in der Sonos-App.
     */
    suspend fun playFavorite(coordinator: SonosDevice, favorite: Favorite) {
        val uri = favorite.uri?.takeIf { it.isNotBlank() }
            ?: throw SonosException("„${favorite.title}“ lässt sich nur in der Sonos-App öffnen")
        val metadata = favorite.metadata.orEmpty()
        if (favorite.isStream) {
            avTransport(coordinator, "SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)
        } else {
            avTransport(coordinator, "RemoveAllTracksFromQueue")
            avTransport(
                coordinator,
                "AddURIToQueue",
                "EnqueuedURI" to uri,
                "EnqueuedURIMetaData" to metadata,
                "DesiredFirstTrackNumberEnqueued" to "0",
                "EnqueueAsNext" to "0",
            )
            avTransport(coordinator, "SetAVTransportURI", "CurrentURI" to "x-rincon-queue:${coordinator.uuid}#0", "CurrentURIMetaData" to "")
        }
        play(coordinator)
    }

    private suspend fun avTransport(device: SonosDevice, action: String, vararg args: Pair<String, String>) =
        soap.call(device, SonosService.AV_TRANSPORT, action, listOf("InstanceID" to "0") + args)

    private suspend fun groupRendering(device: SonosDevice, action: String, vararg args: Pair<String, String>) =
        soap.call(device, SonosService.GROUP_RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args)

    private suspend fun rendering(device: SonosDevice, action: String, vararg args: Pair<String, String>) =
        soap.call(device, SonosService.RENDERING_CONTROL, action, listOf("InstanceID" to "0") + args)

    companion object {
        const val ERROR_TRANSITION_NOT_AVAILABLE = 701
        const val TV_URI_PREFIX = "x-sonos-htastream:"
        const val EQ_NIGHT_MODE = "NightMode"
        /** Sprachverbesserung; so heißt sie bei Beam, Arc und Ray. */
        const val EQ_SPEECH_ENHANCEMENT = "DialogLevel"
    }
}
