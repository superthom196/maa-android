package io.github.superthom196.maa.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import io.github.superthom196.maa.R
import io.github.superthom196.maa.ui.Artwork
import io.github.superthom196.maa.ui.BrowseLevel
import io.github.superthom196.maa.ui.ErrorText
import io.github.superthom196.maa.ui.NowPlaying
import io.github.superthom196.maa.ui.PlayerViewModel

/**
 * A plain list over the same browse tree Android Auto sees, so the library and playback can be
 * tested without a car. Back goes up a level, then closes the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(player: PlayerViewModel, onClose: () -> Unit) {
    // Fresh from the root on each visit from Home, but not on rotation.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            player.startBrowse()
            started = true
        }
    }
    val levels by player.browse.collectAsStateWithLifecycle()
    val np by player.nowPlaying.collectAsStateWithLifecycle()
    val message by player.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            player.consumeMessage()
            snackbar.showSnackbar(it)
        }
    }
    val level = levels.lastOrNull()
    val up = { if (!player.browseBack()) onClose() }
    BackHandler(enabled = levels.size > 1) { player.browseBack() }
    // Each level keeps its own scroll position while it is on the stack.
    val saveable = rememberSaveableStateHolder()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(level?.title ?: stringResource(R.string.browse_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = up) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
        bottomBar = { if (np.hasItem) MiniPlayer(np, player) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (level == null) return@Scaffold
        saveable.SaveableStateProvider(level.key) {
            LevelList(level, player, Modifier.padding(padding))
        }
    }
}

@Composable
private fun LevelList(level: BrowseLevel, player: PlayerViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Page in more rows as the end comes into view (the tree pages Android Auto the same way).
    LaunchedEffect(level.key, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= listState.layoutInfo.totalItemsCount - 10) player.loadMore() }
    }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
        level.item?.let { container ->
            item(key = "play-header") {
                FilledTonalButton(onClick = { player.play(container) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.browse_play))
                }
            }
        }
        items(level.items, key = { it.mediaId }) { item -> BrowseRow(item, player) }
        when {
            level.error != null -> item(key = "error") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ErrorText(level.error)
                    OutlinedButton(onClick = player::loadMore) { Text(stringResource(R.string.retry)) }
                }
            }
            level.loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            level.endReached && level.items.isEmpty() -> item(key = "empty") {
                Text(
                    stringResource(R.string.browse_empty), Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BrowseRow(item: MediaItem, player: PlayerViewModel) {
    val md = item.mediaMetadata
    val browsable = md.isBrowsable == true
    val playable = md.isPlayable == true
    val subtitle = (md.subtitle ?: md.artist)?.toString()
    ListItem(
        modifier = Modifier.clickable(enabled = browsable || playable) { player.open(item) },
        leadingContent = { Artwork(md.artworkUri, 48.dp) },
        headlineContent = { Text(md.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = when {
            browsable && playable -> {
                { IconButton(onClick = { player.play(item) }) { Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.browse_play)) } }
            }
            browsable -> {
                { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
            }
            else -> null
        },
    )
}

@Composable
private fun MiniPlayer(np: NowPlaying, player: PlayerViewModel) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(np.artworkUri, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                np.artist?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            IconButton(onClick = player::playPause) {
                Icon(
                    if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (np.playing) R.string.player_pause else R.string.player_play),
                )
            }
        }
    }
}
