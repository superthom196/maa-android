package io.github.superthom196.maa.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.superthom196.maa.R
import io.github.superthom196.maa.ui.AppViewModel
import io.github.superthom196.maa.ui.ErrorText
import io.github.superthom196.maa.ui.SectionTitle
import io.github.superthom196.maa.ui.UiState

/** First run: pick a server found on the network, or type its address (LAN or Tailscale). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(vm: AppViewModel, ui: UiState) {
    var address by rememberSaveable { mutableStateOf("") }
    val busy = ui.checkingUrl != null

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.connect_title)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(stringResource(R.string.connect_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // At the top: a tapped server that fails is reported where the eye already is.
            ui.connectError?.let { err -> item { ErrorText(err, Modifier.padding(top = 8.dp)) } }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(
                        stringResource(if (ui.discovering) R.string.connect_searching else R.string.connect_found),
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.discovering) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = vm::startDiscovery, enabled = !busy) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.connect_search_again))
                        }
                    }
                }
            }
            if (ui.discovered.isEmpty() && !ui.discovering) {
                item {
                    Text(stringResource(R.string.connect_none_found), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(ui.discovered, key = { it.info.serverId }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        modifier = Modifier.clickable(enabled = !busy) { vm.chooseServer(s) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        leadingContent = { Icon(Icons.Default.Dns, contentDescription = null) },
                        headlineContent = { Text(s.info.name ?: stringResource(R.string.default_server_name)) },
                        supportingContent = { Text(stringResource(R.string.connect_server_line, s.baseUrl, s.info.serverVersion)) },
                        trailingContent = if (ui.checkingUrl == s.baseUrl) {
                            { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                        } else null,
                    )
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(stringResource(R.string.connect_manual_title))
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.connect_address_label)) },
                        placeholder = { Text(stringResource(R.string.connect_address_hint)) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { vm.connectManual(address) }),
                    )
                    Button(onClick = { vm.connectManual(address) }, enabled = !busy && address.isNotBlank()) {
                        if (busy && ui.discovered.none { it.baseUrl == ui.checkingUrl }) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.connect_button))
                    }
                }
            }
        }
    }
}
