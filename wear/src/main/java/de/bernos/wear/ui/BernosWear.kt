package de.bernos.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import de.bernos.wear.PhoneConnection
import de.bernos.wear.R
import de.bernos.wearprotocol.WatchState

interface WearActions {
    fun selectGroup(groupId: String)
    fun playPause()
    fun next()
    fun previous()
    fun setVolume(volume: Int)
    fun moveTo(roomUuid: String)
    fun refresh()
    fun reconnect()
}

private const val HOME = "home"
private const val ROOMS = "rooms"

@Composable
fun BernosWear(state: WatchState?, cover: ImageBitmap?, connection: PhoneConnection, actions: WearActions) {
    // Die Navigation baut ihre Ziele nur einmal auf. Würden sie die Parameter direkt einfangen,
    // bliebe der erste Zustand für immer stehen; daher über State-Objekte lesen.
    val currentState by rememberUpdatedState(state)
    val currentCover by rememberUpdatedState(cover)
    val currentConnection by rememberUpdatedState(connection)
    MaterialTheme {
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = navController, startDestination = HOME) {
                composable(HOME) {
                    val state = currentState
                    when {
                        state == null && currentConnection == PhoneConnection.UNREACHABLE -> PhoneUnreachable(actions::reconnect)
                        state == null -> Connecting()
                        state.selectedGroup == null -> RoomList(state, actions::selectGroup, actions::refresh)
                        else -> PlayerScreen(state, currentCover, actions, onOpenRooms = { navController.navigate(ROOMS) })
                    }
                }
                composable(ROOMS) {
                    RoomList(
                        state = currentState ?: WatchState(),
                        onSelect = { id ->
                            actions.selectGroup(id)
                            navController.popBackStack()
                        },
                        onRefresh = actions::refresh,
                        onMoveTo = { uuid ->
                            actions.moveTo(uuid)
                            navController.popBackStack()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Connecting() {
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.connecting), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PhoneUnreachable(onRetry: () -> Unit) {
    ScreenScaffold {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.phone_unreachable), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.phone_unreachable_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry, label = { Text(stringResource(R.string.retry)) })
        }
    }
}

@Composable
private fun RoomList(
    state: WatchState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onMoveTo: ((String) -> Unit)? = null,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        ScalingLazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text(stringResource(if (state.selectedGroup != null) R.string.control_room else R.string.rooms)) } }
            if (state.groups.isEmpty()) {
                item {
                    Text(
                        stringResource(if (state.discovering) R.string.searching else R.string.no_speakers),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            items(state.groups, key = { it.id }) { group ->
                Button(
                    onClick = { onSelect(group.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (group.id == state.selectedGroupId) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    icon = { Icon(painterResource(R.drawable.ic_speaker), contentDescription = null) },
                    secondaryLabel = if (group.isPlaying) {
                        { Text(stringResource(R.string.playing)) }
                    } else {
                        null
                    },
                    label = { Text(group.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
            // Verschieben nur anbieten, wenn in der gewählten Gruppe etwas läuft oder pausiert ist.
            val hasMusic = state.selectedGroup != null && (state.isPlaying || state.title != null)
            if (onMoveTo != null && hasMusic && state.moveTargets.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.move_music_here), textAlign = TextAlign.Center) } }
                items(state.moveTargets, key = { "move-" + it.uuid }) { room ->
                    Button(
                        onClick = { onMoveTo(room.uuid) },
                        modifier = Modifier.fillMaxWidth().testTag("verschieben-${room.uuid}"),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        icon = { Icon(painterResource(R.drawable.ic_move_here), contentDescription = null) },
                        label = { Text(room.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            state.error?.let { error ->
                item { Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            }
            item {
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    icon = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                    label = { Text(stringResource(R.string.search_again)) },
                )
            }
        }
    }
}

@Composable
private fun PlayerScreen(state: WatchState, cover: ImageBitmap?, actions: WearActions, onOpenRooms: () -> Unit) {
    val group = state.selectedGroup ?: return
    var volume by remember { mutableIntStateOf(state.volume ?: 0) }
    var lastLocalChange by remember { mutableLongStateOf(0L) }
    var rotaryPixels by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }

    // Werte vom Handy übernehmen, außer während an der Lünette gedreht wird.
    LaunchedEffect(state.volume) {
        val remote = state.volume ?: return@LaunchedEffect
        if (System.currentTimeMillis() - lastLocalChange > LOCAL_VOLUME_HOLD_MS) volume = remote
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    rotaryPixels += event.verticalScrollPixels
                    val steps = (rotaryPixels / ROTARY_PIXELS_PER_STEP).toInt()
                    if (steps != 0 && state.volume != null) {
                        rotaryPixels -= steps * ROTARY_PIXELS_PER_STEP
                        volume = (volume + steps).coerceIn(0, 100)
                        lastLocalChange = System.currentTimeMillis()
                        actions.setVolume(volume)
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            if (cover != null) {
                Image(cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            }
            if (state.volume != null) {
                val description = stringResource(R.string.volume_percent, volume)
                LevelIndicator(
                    value = { volume / 100f },
                    modifier = Modifier.align(Alignment.CenterStart).semantics { contentDescription = description },
                )
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Deutlich als Knopf erkennbar: Hier geht es zur Raumliste.
                CompactButton(
                    onClick = onOpenRooms,
                    modifier = Modifier.testTag("raum"),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(painterResource(R.drawable.ic_speaker), contentDescription = stringResource(R.string.choose_room)) },
                    label = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
                Text(
                    state.title ?: state.album ?: stringResource(R.string.nothing_playing),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = state.artist ?: state.album.takeIf { state.title != null }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions::previous) {
                        Icon(painterResource(R.drawable.ic_skip_previous), stringResource(R.string.previous))
                    }
                    FilledIconButton(onClick = actions::playPause, modifier = Modifier.size(IconButtonDefaults.LargeButtonSize)) {
                        Icon(
                            painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                            modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                        )
                    }
                    IconButton(onClick = actions::next) {
                        Icon(painterResource(R.drawable.ic_skip_next), stringResource(R.string.next))
                    }
                }
                state.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

private const val ROTARY_PIXELS_PER_STEP = 30f
private const val LOCAL_VOLUME_HOLD_MS = 1_500L
