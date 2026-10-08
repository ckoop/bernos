package de.bernos.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.bernos.app.BuildConfig
import de.bernos.app.R
import de.bernos.app.bluetooth.HeadphoneBattery
import de.bernos.sonos.BatteryStatus
import de.bernos.sonos.Favorite
import de.bernos.sonos.NowPlaying
import de.bernos.sonos.SonosDevice
import de.bernos.sonos.SonosState
import de.bernos.sonos.ZoneGroup
import kotlinx.coroutines.delay

/** Alles, was die Oberfläche auslösen kann. */
interface BernosActions {
    fun refresh()
    fun addHost(host: String)
    fun selectGroup(groupId: String?)
    fun playPause()
    fun next()
    fun previous()
    fun setVolume(volume: Int)
    fun toggleMute()
    fun setSleepTimer(minutes: Int?)
    fun setRoomVolume(roomUuid: String, volume: Int)
    fun addRoom(roomUuid: String)
    fun removeRoom(roomUuid: String)
    fun moveTo(roomUuid: String)
    fun loadFavorites()
    fun playFavorite(favoriteId: String)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BernosScreen(state: SonosState, actions: BernosActions, headphones: HeadphoneBattery? = null) {
    val selected = state.selectedGroup
    BackHandler(enabled = selected != null) { actions.selectGroup(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = { actions.selectGroup(null) }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton(onClick = actions::refresh, enabled = !state.discovering) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (selected == null) {
                Box(Modifier.weight(1f)) { RoomList(state, headphones, actions::selectGroup, actions::addHost) }
                Text(
                    stringResource(R.string.version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                )
            } else {
                NowPlayingView(selected, state.rooms, state.nowPlaying, state.favorites, actions)
            }
        }
    }
}

@Composable
private fun RoomList(state: SonosState, headphones: HeadphoneBattery?, onSelectGroup: (String) -> Unit, onAddHost: (String) -> Unit) {
    when {
        state.groups.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            items(state.groups, key = { it.id }) { group ->
                val playback = state.groupPlayback[group.id]
                val track = playback?.track
                val battery = group.members.mapNotNull { state.batteries[it.uuid] }.minByOrNull { it.level }
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = {
                        // Was läuft, sonst die Zahl der Lautsprecher.
                        val nowPlaying = listOfNotNull(track?.title ?: track?.album, track?.artist).joinToString(" · ")
                        when {
                            nowPlaying.isNotEmpty() -> Text(
                                if (playback?.isPlaying == true) "▶ $nowPlaying" else nowPlaying,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            group.members.size > 1 -> Text(stringResource(R.string.speakers_count, group.members.size))
                        }
                    },
                    leadingContent = {
                        val art = track?.albumArtUrl
                        if (art != null) {
                            AsyncImage(
                                model = art,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Icon(painterResource(R.drawable.ic_speaker), contentDescription = null)
                        }
                    },
                    trailingContent = battery?.let { { BatteryLabel(it) } },
                    modifier = Modifier.clickable { onSelectGroup(group.id) },
                )
                HorizontalDivider()
            }
            // Die Ace ist kein Raum; sie erscheint nur mit Akkustand, solange sie mit dem Handy verbunden ist.
            headphones?.let { ace ->
                item(key = "kopfhoerer") {
                    ListItem(
                        headlineContent = { Text(ace.name) },
                        supportingContent = { Text(stringResource(R.string.headphones_bluetooth)) },
                        leadingContent = { Icon(painterResource(R.drawable.ic_headphones), contentDescription = null) },
                        trailingContent = { BatteryLabel(BatteryStatus(level = ace.level, charging = false)) },
                    )
                    HorizontalDivider()
                }
            }
        }

        state.discovering -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.searching))
        }

        else -> ManualHostForm(onAddHost)
    }
}

/** Akkustand eines tragbaren Lautsprechers; unter 20 % in Warnfarbe. */
@Composable
private fun BatteryLabel(battery: BatteryStatus) {
    val text = if (battery.charging) {
        stringResource(R.string.battery_charging, battery.level)
    } else {
        stringResource(R.string.battery_level, battery.level)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (!battery.charging && battery.level < LOW_BATTERY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val LOW_BATTERY = 20

/** Fallback, wenn Router oder Firewall die automatische Suche (Multicast) blockieren. */
@Composable
private fun ManualHostForm(onAddHost: (String) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.no_speakers), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(stringResource(R.string.manual_ip)) },
            placeholder = { Text("192.168.1.20") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onAddHost(host) }, enabled = host.isNotBlank()) {
            Text(stringResource(R.string.add))
        }
    }
}

@Composable
private fun NowPlayingView(
    group: ZoneGroup,
    allRooms: List<SonosDevice>,
    nowPlaying: NowPlaying?,
    favorites: List<Favorite>,
    actions: BernosActions,
) {
    val track = nowPlaying?.track
    // Favoriten können sich in der Sonos-App ändern; beim Öffnen eines Raums neu laden.
    LaunchedEffect(group.id) { actions.loadFavorites() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Cover(track?.albumArtUrl, Modifier.widthIn(max = 420.dp).fillMaxWidth())
        Spacer(Modifier.height(24.dp))

        Text(
            track?.title ?: stringResource(R.string.nothing_playing),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        listOfNotNull(track?.artist, track?.album).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))

        Progress(nowPlaying)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = actions::previous, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous), Modifier.size(36.dp))
            }
            val playing = nowPlaying?.isPlaying == true
            FilledIconButton(
                onClick = actions::playPause,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    stringResource(if (playing) R.string.pause else R.string.play),
                    Modifier.size(40.dp),
                )
            }
            IconButton(onClick = actions::next, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next), Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        nowPlaying?.volume?.let { volume ->
            VolumeSlider(group.id, volume, actions::setVolume, muted = nowPlaying.muted == true, onToggleMute = actions::toggleMute)
        }
        Spacer(Modifier.height(8.dp))
        SleepTimerButton(nowPlaying?.sleepTimerRemainingMs, actions::setSleepTimer)
        Spacer(Modifier.height(16.dp))

        FavoritesSection(favorites, actions::playFavorite)

        RoomsSection(group, allRooms, nowPlaying, actions)
    }
}

/** Knopf mit Restzeit; öffnet eine Auswahl der Dauer. Sonos stoppt danach von selbst. */
@Composable
private fun SleepTimerButton(remainingMs: Long?, onSet: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_timer), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (remainingMs == null) {
                    stringResource(R.string.sleep_timer)
                } else {
                    // Aufrunden: Bei 29:30 Restzeit "noch 30 Min".
                    stringResource(R.string.sleep_timer_remaining, ((remainingMs + 59_999) / 60_000).toInt())
                },
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SLEEP_TIMER_MINUTES.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(pluralStringResource(R.plurals.minutes, minutes, minutes)) },
                    onClick = {
                        expanded = false
                        onSet(minutes)
                    },
                )
            }
            if (remainingMs != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_timer_off)) },
                    onClick = {
                        expanded = false
                        onSet(null)
                    },
                )
            }
        }
    }
}

private val SLEEP_TIMER_MINUTES = listOf(15, 30, 45, 60, 90)

/** Sonos-Favoriten als waagerechte Reihe; Verknüpfungen gehen nur in der Sonos-App und fehlen hier. */
@Composable
private fun FavoritesSection(favorites: List<Favorite>, onPlay: (String) -> Unit) {
    if (favorites.isEmpty()) return
    val playable = favorites.filter { it.isPlayable }
    val shortcuts = favorites.size - playable.size
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.favorites))
        if (playable.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playable, key = { it.id }) { favorite ->
                    Column(
                        Modifier
                            .width(104.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPlay(favorite.id) }
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Cover(favorite.albumArtUrl, Modifier.size(96.dp))
                        Text(
                            favorite.title,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (shortcuts > 0) {
            Text(
                pluralStringResource(R.plurals.favorite_shortcuts, shortcuts, shortcuts),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Räume der Gruppe mit eigener Lautstärke sowie weitere Räume zum Dazunehmen oder Hinverschieben. */
@Composable
private fun RoomsSection(group: ZoneGroup, allRooms: List<SonosDevice>, nowPlaying: NowPlaying?, actions: BernosActions) {
    val memberIds = group.members.map { it.uuid }.toSet()
    val others = allRooms.filter { it.uuid !in memberIds }

    Column(Modifier.fillMaxWidth()) {
        if (group.members.size > 1) {
            SectionTitle(stringResource(R.string.rooms_in_group))
            group.members.forEach { member ->
                val isCoordinator = member.uuid == group.coordinator.uuid
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(member.roomName, style = MaterialTheme.typography.bodyLarge)
                        if (isCoordinator) {
                            Text(
                                stringResource(R.string.controls_group),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!isCoordinator) {
                        TextButton(onClick = { actions.removeRoom(member.uuid) }) { Text(stringResource(R.string.remove_room)) }
                    }
                }
                nowPlaying?.memberVolumes?.get(member.uuid)?.let { volume ->
                    VolumeSlider(member.uuid, volume, onVolumeChange = { actions.setRoomVolume(member.uuid, it) })
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }

        if (others.isNotEmpty()) {
            SectionTitle(stringResource(R.string.other_rooms))
            others.forEach { room ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(room.roomName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { actions.addRoom(room.uuid) }) { Text(stringResource(R.string.add_room)) }
                    TextButton(onClick = { actions.moveTo(room.uuid) }) { Text(stringResource(R.string.move_here)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun Cover(url: String?, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(96.dp),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.cover),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Progress(nowPlaying: NowPlaying?) {
    val duration = nowPlaying?.durationMs ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nowPlaying) {
        now = System.currentTimeMillis()
        // Nur während der Wiedergabe weiterzählen.
        while (nowPlaying.isPlaying) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val position = nowPlaying.estimatedPositionMs(now) ?: 0L
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun VolumeSlider(
    groupId: String,
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    muted: Boolean = false,
    /** Nur bei der Gruppenlautstärke: Symbol wird zum Knopf für Stummschalten. */
    onToggleMute: (() -> Unit)? = null,
) {
    // Während des Ziehens den lokalen Wert zeigen, damit Aktualisierungen vom Lautsprecher nicht dazwischenfunken.
    var dragging by remember(groupId) { mutableStateOf(false) }
    var dragValue by remember(groupId) { mutableFloatStateOf(volume.toFloat()) }
    val value = if (dragging) dragValue else volume.toFloat()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onToggleMute == null) {
            Icon(painterResource(R.drawable.ic_volume), contentDescription = stringResource(R.string.volume))
        } else {
            IconButton(onClick = onToggleMute) {
                Icon(
                    painterResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume),
                    contentDescription = stringResource(if (muted) R.string.unmute else R.string.mute),
                    tint = if (muted) MaterialTheme.colorScheme.error else LocalContentColor.current,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onVolumeChange(dragValue.toInt())
            },
            valueRange = 0f..100f,
            // Stumm bleibt die Lautstärke einstellbar, wirkt aber erst nach dem Aufheben.
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).alpha(if (muted) 0.5f else 1f),
        )
        Text(value.toInt().toString(), style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
