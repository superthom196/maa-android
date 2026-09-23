package io.github.superthom196.maa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.superthom196.maa.R
import io.github.superthom196.maa.data.ServerConfig
import io.github.superthom196.maa.playback.PrefetchStatus
import io.github.superthom196.maa.ui.AppViewModel
import io.github.superthom196.maa.ui.Artwork
import io.github.superthom196.maa.ui.ConnectionKind
import io.github.superthom196.maa.ui.ErrorText
import io.github.superthom196.maa.ui.Formatters
import io.github.superthom196.maa.ui.NowPlaying
import io.github.superthom196.maa.ui.PlayerViewModel
import io.github.superthom196.maa.ui.PluginState
import io.github.superthom196.maa.ui.Screen
import io.github.superthom196.maa.ui.ServerUrls
import io.github.superthom196.maa.ui.UiState

/** Status at a glance (connection, plugin, what is playing and how much of it is on the phone). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, player: PlayerViewModel, ui: UiState) {
    val cfg by vm.server.collectAsStateWithLifecycle()
    val current by vm.currentBase.collectAsStateWithLifecycle()
    val prefetch by vm.prefetch.collectAsStateWithLifecycle()
    val np by player.nowPlaying.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(cfg, current)
            PluginCard(ui.plugin, onRetry = vm::refreshPlugin)
            NowPlayingCard(np, player, prefetch)
            Button(onClick = { vm.navigate(Screen.Browse) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_browse))
            }
        }
    }
}

@Composable
private fun StatusCard(icon: ImageVector, warn: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (warn) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
    else CardDefaults.elevatedCardColors()
    ElevatedCard(Modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun ConnectionCard(cfg: ServerConfig?, current: String?) {
    val kind = ServerUrls.connectionKind(current, cfg)
    val (icon, label) = when (kind) {
        ConnectionKind.LAN -> Icons.Default.Wifi to R.string.home_via_lan
        ConnectionKind.TAILSCALE -> Icons.Default.VpnLock to R.string.home_via_tailscale
        ConnectionKind.REMOTE -> Icons.Default.Public to R.string.home_via_remote
        ConnectionKind.UNREACHABLE -> Icons.Default.CloudOff to R.string.home_unreachable
    }
    StatusCard(icon) {
        Text(cfg?.name ?: stringResource(R.string.default_server_name), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
        current?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PluginCard(state: PluginState, onRetry: () -> Unit) {
    when (state) {
        PluginState.Checking -> StatusCard(Icons.Default.Extension) {
            Text(stringResource(R.string.plugin_checking), style = MaterialTheme.typography.bodyMedium)
        }
        is PluginState.Found -> StatusCard(Icons.Default.Extension) {
            Text(stringResource(R.string.plugin_found, state.info.version), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.plugin_format, state.info.format), style = MaterialTheme.typography.bodyMedium)
        }
        PluginState.Missing -> StatusCard(Icons.Default.Warning, warn = true) {
            Text(stringResource(R.string.plugin_missing), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        is PluginState.Unknown -> StatusCard(Icons.Default.Extension) {
            Text(stringResource(R.string.plugin_unknown, state.reason), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
private fun NowPlayingCard(np: NowPlaying, player: PlayerViewModel, prefetch: PrefetchStatus) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.home_now_playing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            when {
                !np.connected && np.error != null -> {
                    ErrorText(np.error)
                    TextButton(onClick = player::reconnect) { Text(stringResource(R.string.retry)) }
                }
                !np.hasItem -> Text(stringResource(R.string.home_nothing_playing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(np.artworkUri, 88.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(np.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            np.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    ProgressRow(player)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = player::previous, enabled = np.canPrevious) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.player_previous))
                        }
                        Spacer(Modifier.width(16.dp))
                        FilledIconButton(onClick = player::playPause, modifier = Modifier.size(56.dp)) {
                            Icon(
                                if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(if (np.playing) R.string.player_pause else R.string.player_play),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = player::next, enabled = np.canNext) {
                            Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.player_next))
                        }
                    }
                    if (np.error != null) ErrorText(np.error)
                }
            }
            val onPhone = stringResource(R.string.prefetch_on_phone)
            Formatters.prefetchLine(prefetch) { cached, targets -> String.format(onPhone, cached, targets) }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Separate composable so the 500 ms position ticks only recompose this row. */
@Composable
private fun ProgressRow(player: PlayerViewModel) {
    val p by player.progress.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(Formatters.duration(p.positionMs), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(Formatters.duration(p.durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}
