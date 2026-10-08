package de.bernos.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Gesamtzustand, den die Oberfläche (App, Benachrichtigung, Uhr) anzeigt. */
data class SonosState(
    val discovering: Boolean = false,
    val groups: List<ZoneGroup> = emptyList(),
    val selectedGroupId: String? = null,
    val nowPlaying: NowPlaying? = null,
    val error: String? = null,
    /** Sonos-Favoriten, auch nicht abspielbare Verknüpfungen (siehe [Favorite.isPlayable]). */
    val favorites: List<Favorite> = emptyList(),
    /** Was in jeder Gruppe läuft, nach Gruppen-ID – für die Raumliste. */
    val groupPlayback: Map<String, GroupPlayback> = emptyMap(),
    /** Akkustand tragbarer Lautsprecher, nach UUID. */
    val batteries: Map<String, BatteryStatus> = emptyMap(),
) {
    val selectedGroup: ZoneGroup? get() = groups.firstOrNull { it.id == selectedGroupId }

    /** Alle Räume des Systems, alphabetisch. */
    val rooms: List<SonosDevice> get() = groups.flatMap { it.members }.sortedBy { it.roomName.lowercase() }
}

/**
 * Zentrale Steuerung: findet die Lautsprecher, verfolgt die ausgewählte Gruppe und
 * führt Befehle aus. Alle Methoden sind threadsicher und blockieren nicht.
 */
class SonosController(
    private val scope: CoroutineScope,
    private val discovery: SsdpDiscovery = SsdpDiscovery(),
    http: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val groupingRetryDelayMs: Long = 500,
) {
    private val player = SonosPlayerClient(SoapClient(http))
    private val subscriber = GenaSubscriber(http)
    private val eventServer = GenaEventServer(scope)

    private val _state = MutableStateFlow(SonosState())
    val state: StateFlow<SonosState> = _state.asStateFlow()

    private val knownHosts = linkedSetOf<String>()
    private val refreshMutex = Mutex()
    @Volatile private var trackingJob: Job? = null
    @Volatile private var overviewJob: Job? = null

    /** Lautsprecher ohne Akku; werden nicht erneut gefragt. */
    private val withoutBattery: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    @Volatile private var subscriptions: List<GenaSubscriber.Subscription> = emptyList()

    init {
        scope.launch {
            eventServer.events.collect { event ->
                val subscription = subscriptions.firstOrNull { it.sid == event.sid } ?: return@collect
                if (subscription.service == SonosService.ZONE_GROUP_TOPOLOGY) refreshTopology() else refreshNowPlaying()
            }
        }
    }

    /** Sucht Lautsprecher im Netz und lädt die Raumaufteilung. */
    fun discover() {
        if (_state.value.discovering) return
        scope.launch {
            _state.update { it.copy(discovering = true, error = null) }
            try {
                val found = discovery.search()
                synchronized(knownHosts) { knownHosts.addAll(found) }
                refreshTopology()
                if (_state.value.groups.isEmpty()) {
                    _state.update { it.copy(error = "Keine Sonos-Lautsprecher gefunden. Ist das Handy im selben WLAN?") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Suche fehlgeschlagen") }
            } finally {
                _state.update { it.copy(discovering = false) }
            }
        }
    }

    /**
     * Lautsprecher per IP-Adresse hinzufügen, falls die automatische Suche im Netz blockiert ist.
     * Optional mit Port, z. B. `192.168.1.20:1400`.
     */
    fun addHost(host: String) {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return
        val added = synchronized(knownHosts) { knownHosts.add(trimmed) }
        if (added || _state.value.groups.isEmpty()) scope.launch { refreshTopology() }
    }

    /** Lädt Raumaufteilung, Wiedergabe, Übersicht und Favoriten sofort neu, z. B. wenn die Uhr-App geöffnet wird. */
    fun refresh() {
        scope.launch {
            refreshTopology()
            refreshNowPlaying()
            refreshOverview(withBatteries = true)
            refreshFavorites()
        }
    }

    /**
     * Fragt regelmäßig ab, was in allen Räumen läuft, und den Akkustand – solange eine Übersicht
     * sichtbar ist (Raumliste). Mit [stopOverview] beenden.
     */
    fun startOverview() {
        if (overviewJob?.isActive == true) return
        overviewJob = scope.launch {
            var round = 0
            while (true) {
                refreshOverview(withBatteries = round % BATTERY_EVERY_N_ROUNDS == 0)
                round++
                delay(OVERVIEW_INTERVAL_MS)
            }
        }
    }

    fun stopOverview() {
        overviewJob?.cancel()
        overviewJob = null
    }

    /** Schlaftimer der ausgewählten Gruppe; [minutes] = `null` schaltet ihn aus. */
    fun setSleepTimer(minutes: Int?) = command { player.setSleepTimer(it.coordinator, minutes) }

    /** Lädt die Favoriten neu, z. B. wenn die Liste geöffnet wird; in der Sonos-App können sie sich ändern. */
    fun loadFavorites() {
        scope.launch { refreshFavorites() }
    }

    /** Spielt einen Favoriten in der ausgewählten Gruppe ab. */
    fun playFavorite(favoriteId: String) {
        val favorite = _state.value.favorites.firstOrNull { it.id == favoriteId } ?: return
        command { player.playFavorite(it.coordinator, favorite) }
    }

    /**
     * Schaltet die Soundbar der ausgewählten Gruppe auf den Fernseher. Spielt sie in einer Gruppe
     * mit, deren Steuerung bei einem anderen Raum liegt, verlässt sie diese; die Auswahl folgt ihr.
     */
    fun switchToTv() {
        val soundbar = _state.value.selectedGroup?.homeTheater ?: return
        groupingCommand {
            player.switchToTv(soundbar)
            soundbar.uuid
        }
    }

    fun setNightMode(enabled: Boolean) = setHomeTheaterEq(SonosPlayerClient.EQ_NIGHT_MODE, enabled) { copy(nightMode = enabled) }

    fun setSpeechEnhancement(enabled: Boolean) =
        setHomeTheaterEq(SonosPlayerClient.EQ_SPEECH_ENHANCEMENT, enabled) { copy(speechEnhancement = enabled) }

    private fun setHomeTheaterEq(type: String, enabled: Boolean, optimistic: HomeTheaterState.() -> HomeTheaterState) {
        val soundbar = _state.value.selectedGroup?.homeTheater ?: return
        // Sofort anzeigen; die nächste Abfrage bestätigt den Wert.
        _state.update { s -> s.copy(nowPlaying = s.nowPlaying?.let { it.copy(homeTheater = it.homeTheater?.optimistic()) }) }
        command { player.setEq(soundbar, type, if (enabled) 1 else 0) }
    }

    fun selectGroup(groupId: String?) {
        _state.update { it.copy(selectedGroupId = groupId, nowPlaying = null, error = null) }
        startTracking()
    }

    fun play() = command { player.play(it.coordinator) }
    fun pause() = command { player.pause(it.coordinator) }
    fun togglePlayPause() = if (_state.value.nowPlaying?.isPlaying == true) pause() else play()
    fun next() = command { player.next(it.coordinator) }
    fun previous() = command { player.previous(it.coordinator) }

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        // Sofort anzeigen, damit Schieberegler und Lautstärketasten nicht springen.
        _state.update { s -> s.copy(nowPlaying = s.nowPlaying?.copy(volume = clamped)) }
        command(refreshAfter = false) { player.setGroupVolume(it.coordinator, clamped) }
    }

    fun setMuted(muted: Boolean) {
        // Sofort anzeigen; die nächste Abfrage bestätigt den Wert.
        _state.update { s -> s.copy(nowPlaying = s.nowPlaying?.copy(muted = muted)) }
        command { player.setGroupMute(it.coordinator, muted) }
    }

    fun toggleMuted() = setMuted(_state.value.nowPlaying?.muted != true)

    /** Lautstärke eines einzelnen Raums der ausgewählten Gruppe. */
    fun setRoomVolume(roomUuid: String, volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        val device = findRoom(roomUuid) ?: return
        _state.update { s ->
            s.copy(nowPlaying = s.nowPlaying?.let { it.copy(memberVolumes = it.memberVolumes + (roomUuid to clamped)) })
        }
        command { player.setVolume(device, clamped) }
    }

    /** Nimmt einen weiteren Raum in die ausgewählte Gruppe auf; er spielt dann dasselbe. */
    fun addRoomToGroup(roomUuid: String) {
        val room = findRoom(roomUuid) ?: return
        groupingCommand { group ->
            if (group.members.any { it.uuid == roomUuid }) return@groupingCommand null
            player.joinGroup(room, group.coordinator.uuid)
            null
        }
    }

    /** Nimmt einen Raum aus der ausgewählten Gruppe heraus. Der Raum, der die Gruppe steuert, bleibt drin. */
    fun removeRoomFromGroup(roomUuid: String) {
        val room = findRoom(roomUuid) ?: return
        groupingCommand { group ->
            if (group.coordinator.uuid == roomUuid) {
                throw SonosException("${room.roomName} steuert die Gruppe. Verschiebe die Musik zuerst in einen anderen Raum.")
            }
            if (group.members.none { it.uuid == roomUuid }) return@groupingCommand null
            player.leaveGroup(room)
            null
        }
    }

    /**
     * Verschiebt die laufende Musik der ausgewählten Gruppe in den Raum [roomUuid]: Der Raum
     * übernimmt die Wiedergabe, der bisher steuernde Raum verstummt. Die Auswahl folgt der Musik.
     */
    fun movePlaybackTo(roomUuid: String) {
        val target = findRoom(roomUuid) ?: return
        groupingCommand { group ->
            if (group.coordinator.uuid == roomUuid) return@groupingCommand roomUuid
            if (group.members.none { it.uuid == roomUuid }) player.joinGroup(target, group.coordinator.uuid)
            // Der neue Raum braucht einen Moment, bis er als Gruppenmitglied gilt.
            var attempt = 0
            while (true) {
                try {
                    player.delegateCoordination(group.coordinator, roomUuid, rejoinGroup = false)
                    break
                } catch (e: SonosException) {
                    if (++attempt >= GROUPING_RETRIES) throw e
                    delay(groupingRetryDelayMs)
                }
            }
            roomUuid
        }
    }

    /** Nimmt die Verfolgung der ausgewählten Gruppe wieder auf, falls sie mit [stopTracking] beendet wurde. */
    fun resumeTracking() {
        if (trackingJob == null && _state.value.selectedGroupId != null) startTracking()
    }

    /**
     * Beendet Abfragen, Ereignis-Abos und den Ereignis-Server, z. B. wenn die Steuerung
     * geschlossen wird. Die Raumauswahl bleibt erhalten.
     */
    fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        val old = subscriptions
        subscriptions = emptyList()
        scope.launch(NonCancellable) { old.forEach { subscriber.unsubscribe(it) } }
        eventServer.stop()
    }

    private fun findRoom(uuid: String): SonosDevice? = _state.value.rooms.firstOrNull { it.uuid == uuid }

    /**
     * Führt eine Änderung der Raumaufteilung aus und lädt sie danach neu. [action] kann die
     * UUID eines Raums zurückgeben, dessen Gruppe danach ausgewählt werden soll.
     */
    private fun groupingCommand(action: suspend (ZoneGroup) -> String?) {
        val group = _state.value.selectedGroup ?: return
        scope.launch {
            var follow: String? = null
            try {
                follow = action(group)
                _state.update { it.copy(error = null) }
            } catch (e: SonosException) {
                _state.update { it.copy(error = e.message) }
            }
            refreshTopology(preferredCoordinatorUuid = follow)
            refreshNowPlaying()
            // Sonos übernimmt Gruppenänderungen nicht immer sofort; einmal nachladen.
            delay(TOPOLOGY_SETTLE_MS)
            refreshTopology(preferredCoordinatorUuid = follow)
            refreshNowPlaying()
        }
    }

    private fun command(refreshAfter: Boolean = true, action: suspend (ZoneGroup) -> Unit) {
        val group = _state.value.selectedGroup ?: return
        scope.launch {
            try {
                action(group)
                _state.update { it.copy(error = null) }
            } catch (e: SonosException) {
                val message = if (e.upnpErrorCode == SonosPlayerClient.ERROR_TRANSITION_NOT_AVAILABLE) {
                    "Bei dieser Quelle nicht möglich"
                } else {
                    e.message
                }
                _state.update { it.copy(error = message) }
            }
            if (refreshAfter) refreshNowPlaying()
        }
    }

    private fun startTracking() {
        trackingJob?.cancel()
        val old = subscriptions
        subscriptions = emptyList()
        val group = _state.value.selectedGroup ?: run {
            trackingJob = null
            scope.launch(NonCancellable) { old.forEach { subscriber.unsubscribe(it) } }
            return
        }
        trackingJob = scope.launch {
            withContext(NonCancellable) { old.forEach { subscriber.unsubscribe(it) } }
            subscribe(group.coordinator)
            var sinceRenewMs = 0L
            while (true) {
                refreshNowPlaying()
                // Ereignisse lösen sofortige Aktualisierungen aus; das Abfragen hier ist nur das Sicherheitsnetz.
                val interval = if (subscriptions.isEmpty()) POLL_WITHOUT_EVENTS_MS else POLL_WITH_EVENTS_MS
                delay(interval)
                sinceRenewMs += interval
                if (sinceRenewMs >= RENEW_INTERVAL_MS) {
                    sinceRenewMs = 0
                    subscriptions = subscriptions.mapNotNull { runCatching { subscriber.renew(it) }.getOrNull() }
                    if (subscriptions.isEmpty()) subscribe(group.coordinator)
                }
            }
        }
    }

    private suspend fun subscribe(coordinator: SonosDevice) {
        try {
            eventServer.start()
            subscriptions = listOf(
                SonosService.AV_TRANSPORT,
                SonosService.GROUP_RENDERING_CONTROL,
                SonosService.ZONE_GROUP_TOPOLOGY,
            ).map { subscriber.subscribe(coordinator, it, eventServer.port) }
        } catch (e: Exception) {
            // Ohne Ereignisse funktioniert die App weiter, sie fragt dann nur häufiger nach.
            subscriptions = emptyList()
        }
    }

    private suspend fun refreshFavorites() {
        val device = _state.value.groups.firstOrNull()?.coordinator ?: return
        val favorites = try {
            player.favorites(device)
        } catch (e: SonosException) {
            return // Ohne Favoriten funktioniert alles andere weiter.
        }
        _state.update { it.copy(favorites = favorites) }
    }

    private suspend fun refreshTopology(preferredCoordinatorUuid: String? = null) {
        val hosts = synchronized(knownHosts) { knownHosts.toList() }
        for (address in hosts) {
            val groups = try {
                player.zoneGroups(deviceForAddress(address))
            } catch (e: Exception) {
                continue
            }
            synchronized(knownHosts) { groups.flatMap { it.members }.forEach { knownHosts.add(addressOf(it)) } }
            val before = _state.value
            val selected = preferredCoordinatorUuid?.let { uuid -> groups.find { it.coordinator.uuid == uuid } }
                ?: before.selectedGroupId?.let { id -> groups.find { it.id == id } }
                // Nach dem Umgruppieren bekommt die Gruppe eine neue ID; dem bisherigen Koordinator folgen.
                ?: before.selectedGroup?.let { old -> groups.find { g -> g.members.any { it.uuid == old.coordinator.uuid } } }
                ?: groups.singleOrNull()
            // Eine frühere "Keine Lautsprecher gefunden"-Meldung ist jetzt überholt, andere Fehler bleiben stehen.
            val error = if (before.groups.isEmpty()) null else before.error
            _state.update { it.copy(groups = groups, selectedGroupId = selected?.id, error = error) }
            if (before.groups.isEmpty()) {
                refreshFavorites()
                refreshOverview(withBatteries = true)
            }
            if (selected?.coordinator?.uuid != before.selectedGroup?.coordinator?.uuid) {
                _state.update { it.copy(nowPlaying = null) }
                startTracking()
            }
            return
        }
    }

    /**
     * Titel für die Anzeige. Bei Gruppen mit Soundbar wird zuerst geprüft, ob der Fernseher läuft;
     * dessen Metadaten sind nichtssagend, angezeigt wird dann [TV_TITLE].
     * @return Titel und ob der Fernseher läuft.
     */
    private suspend fun trackOf(group: ZoneGroup, track: TrackInfo?): Pair<TrackInfo?, Boolean> {
        if (group.homeTheater != null) {
            val uri = runCatching { player.currentUri(group.coordinator) }.getOrNull()
            if (uri?.startsWith(SonosPlayerClient.TV_URI_PREFIX) == true) return TrackInfo(TV_TITLE, null, null, null) to true
        }
        return withSource(group.coordinator, track) to false
    }

    /** Fehlt Album oder Cover (typisch bei Radio), helfen Name und Logo der Quelle aus. */
    private suspend fun withSource(coordinator: SonosDevice, track: TrackInfo?): TrackInfo? {
        if (track?.album != null && track.albumArtUrl != null) return track
        val source = runCatching { player.sourceInfo(coordinator) }.getOrNull() ?: return track
        val base = track ?: TrackInfo(null, null, null, null)
        return base.copy(
            album = base.album ?: source.title?.takeIf { it != base.title },
            albumArtUrl = base.albumArtUrl ?: source.albumArtUrl,
        )
    }

    /** Was läuft in jeder Gruppe, und (wenn gewünscht) der Akkustand tragbarer Lautsprecher. */
    private suspend fun refreshOverview(withBatteries: Boolean) = coroutineScope {
        val groups = _state.value.groups
        if (groups.isEmpty()) return@coroutineScope
        val playback = groups.map { group ->
            async {
                runCatching {
                    val coordinator = group.coordinator
                    val transport = player.transportState(coordinator)
                    val (track, _) = trackOf(group, player.positionInfo(coordinator).track)
                    group.id to GroupPlayback(transport, track)
                }.getOrNull()
            }
        }.awaitAll().filterNotNull().toMap()
        val batteries = if (withBatteries) {
            groups.flatMap { it.members }.filter { it.uuid !in withoutBattery }.map { member ->
                async {
                    val battery = runCatching { player.battery(member) }.getOrNull()
                    if (battery == null) withoutBattery += member.uuid
                    battery?.let { member.uuid to it }
                }
            }.awaitAll().filterNotNull().toMap()
        } else {
            null
        }
        val ids = groups.map { it.id }.toSet()
        _state.update {
            it.copy(
                // Gruppen, die es nicht mehr gibt, fallen heraus.
                groupPlayback = (it.groupPlayback + playback).filterKeys { id -> id in ids },
                batteries = batteries ?: it.batteries,
            )
        }
    }

    private suspend fun refreshNowPlaying() {
        val group = _state.value.selectedGroup ?: return
        refreshMutex.withLock {
            try {
                val coordinator = group.coordinator
                val transport = player.transportState(coordinator)
                val position = player.positionInfo(coordinator)
                val volume = runCatching { player.groupVolume(coordinator) }.getOrNull()
                val muted = runCatching { player.groupMute(coordinator) }.getOrNull()
                val memberVolumes = if (group.members.size > 1) {
                    group.members.mapNotNull { member ->
                        runCatching { player.volume(member) }.getOrNull()?.let { member.uuid to it }
                    }.toMap()
                } else {
                    volume?.let { mapOf(coordinator.uuid to it) } ?: emptyMap()
                }
                val (track, tvActive) = trackOf(group, position.track)
                val sleepTimer = runCatching { player.sleepTimerRemaining(coordinator) }.getOrNull()
                val homeTheater = group.homeTheater?.let { soundbar ->
                    HomeTheaterState(
                        deviceUuid = soundbar.uuid,
                        tvActive = tvActive,
                        nightMode = runCatching { player.eq(soundbar, SonosPlayerClient.EQ_NIGHT_MODE) }.getOrNull()?.let { it != 0 },
                        speechEnhancement = runCatching { player.eq(soundbar, SonosPlayerClient.EQ_SPEECH_ENHANCEMENT) }.getOrNull()?.let { it != 0 },
                    )
                }
                val nowPlaying = NowPlaying(
                    groupId = group.id,
                    transportState = transport,
                    track = track,
                    durationMs = position.durationMs?.takeIf { it > 0 },
                    positionMs = position.positionMs,
                    positionCapturedAtMs = clock(),
                    volume = volume,
                    muted = muted,
                    memberVolumes = memberVolumes,
                    sleepTimerRemainingMs = sleepTimer,
                    homeTheater = homeTheater,
                )
                _state.update {
                    // Die Übersicht gleich mitpflegen, damit die Raumliste zur Wiedergabe passt.
                    val playback = it.groupPlayback + (group.id to GroupPlayback(transport, track))
                    if (it.selectedGroupId == group.id) it.copy(nowPlaying = nowPlaying, groupPlayback = playback) else it
                }
            } catch (e: SonosException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    companion object {
        /** Anzeige, solange die Soundbar den Fernsehton spielt. */
        const val TV_TITLE = "Fernseher"
        private const val GROUPING_RETRIES = 5
        private const val TOPOLOGY_SETTLE_MS = 1_500L
        private const val POLL_WITH_EVENTS_MS = 15_000L
        private const val POLL_WITHOUT_EVENTS_MS = 3_000L
        private const val OVERVIEW_INTERVAL_MS = 10_000L
        /** Akku nur jede sechste Runde (etwa jede Minute) abfragen. */
        private const val BATTERY_EVERY_N_ROUNDS = 6
        private const val RENEW_INTERVAL_MS = (GenaSubscriber.DEFAULT_TIMEOUT_SECONDS * 1000L) / 2

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        /** "host" oder "host:port" → Gerät mit Platzhalter-UUID, nur zum Abfragen der Raumaufteilung. */
        internal fun deviceForAddress(address: String): SonosDevice {
            val host = address.substringBeforeLast(':', address)
            val port = address.substringAfterLast(':', "").toIntOrNull() ?: SonosDevice.DEFAULT_PORT
            return SonosDevice(uuid = "", roomName = host, host = host, port = port)
        }

        internal fun addressOf(device: SonosDevice): String =
            if (device.port == SonosDevice.DEFAULT_PORT) device.host else "${device.host}:${device.port}"
    }
}
