package io.github.superthom196.maa.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.superthom196.maa.BuildConfig
import io.github.superthom196.maa.R
import io.github.superthom196.maa.ui.AppViewModel
import io.github.superthom196.maa.ui.Formatters
import io.github.superthom196.maa.ui.PluginState
import io.github.superthom196.maa.ui.SectionTitle
import io.github.superthom196.maa.ui.UiState
import kotlin.math.roundToInt

private enum class Confirm { ClearCache, SignOut }

/** Server addresses, look-ahead, phone cache, plugin format, sign out, version. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: AppViewModel, ui: UiState, onClose: () -> Unit) {
    val cfg by vm.server.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val usedBytes by vm.cacheUsedBytes.collectAsStateWithLifecycle()
    val server = cfg ?: return
    var confirm by rememberSaveable { mutableStateOf<Confirm?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- server addresses
            SectionTitle(stringResource(R.string.settings_server))
            Text(
                listOfNotNull(server.name, server.username?.let { stringResource(R.string.settings_signed_in_as, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            var lan by rememberSaveable(server.lanUrl) { mutableStateOf(server.lanUrl.orEmpty()) }
            var remote by rememberSaveable(server.remoteUrl) { mutableStateOf(server.remoteUrl.orEmpty()) }
            UrlField(lan, { lan = it }, R.string.settings_lan_url, R.string.settings_lan_hint, ui.lanError, !ui.urlsBusy)
            UrlField(remote, { remote = it }, R.string.settings_remote_url, R.string.settings_remote_hint, ui.remoteError, !ui.urlsBusy)
            Text(stringResource(R.string.settings_urls_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.saveUrls(lan, remote) }, enabled = !ui.urlsBusy) {
                    if (ui.urlsBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.settings_save))
                }
                if (ui.urlsSaved) {
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.settings_saved), color = MaterialTheme.colorScheme.primary)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- look-ahead
            SectionTitle(stringResource(R.string.settings_lookahead))
            val range = Formatters.LOOKAHEAD_RANGE
            var ahead by rememberSaveable(settings.lookahead) { mutableFloatStateOf(settings.lookahead.coerceIn(range).toFloat()) }
            Text(stringResource(R.string.settings_lookahead_label, ahead.roundToInt()), style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = ahead,
                onValueChange = { ahead = it },
                onValueChangeFinished = { vm.setLookahead(ahead.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = range.last - range.first - 1,
            )
            Text(stringResource(R.string.settings_lookahead_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- phone cache
            SectionTitle(stringResource(R.string.settings_cache))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Formatters.CACHE_CHOICES_MB.forEach { mb ->
                    FilterChip(
                        selected = settings.cacheMb == mb,
                        onClick = { vm.setCacheMb(mb) },
                        label = { Text(Formatters.cacheSize(mb)) },
                    )
                }
            }
            Text(stringResource(R.string.settings_cache_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.settings_cache_used, Formatters.bytes(usedBytes), Formatters.cacheSize(settings.cacheMb)),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { confirm = Confirm.ClearCache }) { Text(stringResource(R.string.settings_clear_cache)) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- plugin
            SectionTitle(stringResource(R.string.settings_plugin))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluginSummary(ui.plugin, server.format), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::refreshPlugin, enabled = ui.plugin != PluginState.Checking) { Text(stringResource(R.string.settings_refresh)) }
            }
            Text(stringResource(R.string.settings_format_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // ---- account + about
            OutlinedButton(
                onClick = { confirm = Confirm.SignOut },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_sign_out)) }
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
            )
        }
    }

    when (confirm) {
        Confirm.ClearCache -> ConfirmDialog(
            title = R.string.settings_clear_cache_title, text = R.string.settings_clear_cache_text, confirm = R.string.settings_clear,
            onConfirm = { vm.clearCache(); confirm = null }, onDismiss = { confirm = null },
        )
        Confirm.SignOut -> ConfirmDialog(
            title = R.string.settings_sign_out_title, text = R.string.settings_sign_out_text, confirm = R.string.settings_sign_out,
            onConfirm = { confirm = null; vm.signOut() }, onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@Composable
private fun pluginSummary(state: PluginState, configuredFormat: String): String = when (state) {
    PluginState.Checking -> stringResource(R.string.plugin_checking)
    is PluginState.Found -> stringResource(R.string.settings_plugin_found, state.info.format, state.info.version)
    PluginState.Missing -> stringResource(R.string.plugin_missing)
    is PluginState.Unknown -> stringResource(R.string.settings_plugin_unknown, configuredFormat, state.reason)
}

@Composable
private fun UrlField(value: String, onChange: (String) -> Unit, label: Int, hint: Int, error: String?, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        placeholder = { Text(stringResource(hint)) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
    )
}

@Composable
private fun ConfirmDialog(title: Int, text: Int, confirm: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
