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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.bernos.app.R
import de.bernos.sonos.NowPlaying
import de.bernos.sonos.SonosState
import de.bernos.sonos.ZoneGroup
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BernosScreen(
    state: SonosState,
    onRefresh: () -> Unit,
    onAddHost: (String) -> Unit,
    onSelectGroup: (String?) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    val selected = state.selectedGroup
    BackHandler(enabled = selected != null) { onSelectGroup(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = { onSelectGroup(null) }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (selected == null) {
                        IconButton(onClick = onRefresh, enabled = !state.discovering) {
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
                RoomList(state, onSelectGroup, onAddHost)
            } else {
                NowPlayingView(selected, state.nowPlaying, onPlayPause, onNext, onPrevious, onVolumeChange)
            }
        }
    }
}

@Composable
private fun RoomList(state: SonosState, onSelectGroup: (String) -> Unit, onAddHost: (String) -> Unit) {
    when {
        state.groups.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
            items(state.groups, key = { it.id }) { group ->
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = {
                        if (group.members.size > 1) Text("${group.members.size} Lautsprecher")
                    },
                    leadingContent = { Icon(painterResource(R.drawable.ic_speaker), contentDescription = null) },
                    modifier = Modifier.clickable { onSelectGroup(group.id) },
                )
                HorizontalDivider()
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
    nowPlaying: NowPlaying?,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    val track = nowPlaying?.track
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
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous), Modifier.size(36.dp))
            }
            val playing = nowPlaying?.isPlaying == true
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    stringResource(if (playing) R.string.pause else R.string.play),
                    Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next), Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        nowPlaying?.volume?.let { volume -> VolumeSlider(group.id, volume, onVolumeChange) }
    }
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
        while (true) {
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
private fun VolumeSlider(groupId: String, volume: Int, onVolumeChange: (Int) -> Unit) {
    // Während des Ziehens den lokalen Wert zeigen, damit Aktualisierungen vom Lautsprecher nicht dazwischenfunken.
    var dragging by remember(groupId) { mutableStateOf(false) }
    var dragValue by remember(groupId) { mutableFloatStateOf(volume.toFloat()) }
    val value = if (dragging) dragValue else volume.toFloat()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_volume), contentDescription = stringResource(R.string.volume))
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
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
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
