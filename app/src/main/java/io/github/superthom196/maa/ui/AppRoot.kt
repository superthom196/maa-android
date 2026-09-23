package io.github.superthom196.maa.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.superthom196.maa.ui.screens.BrowseScreen
import io.github.superthom196.maa.ui.screens.ConnectScreen
import io.github.superthom196.maa.ui.screens.HomeScreen
import io.github.superthom196.maa.ui.screens.LoginScreen
import io.github.superthom196.maa.ui.screens.SettingsScreen

/**
 * Picks the screen from [AppViewModel]'s phase and back stack. No navigation library: three
 * phases and three main screens fit in a `when`. [PlayerViewModel] is only created once signed
 * in, so the playback service is not bound before there is anything to play.
 */
@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (ui.phase) {
        Phase.Connect -> ConnectScreen(vm, ui)
        Phase.Login -> LoginScreen(vm, ui)
        Phase.Main -> {
            val player: PlayerViewModel = viewModel()
            BackHandler(enabled = ui.stack.size > 1) { vm.back() }
            when (ui.stack.last()) {
                Screen.Home -> HomeScreen(vm, player, ui)
                Screen.Browse -> BrowseScreen(player, onClose = { vm.back() })
                Screen.Settings -> SettingsScreen(vm, ui, onClose = { vm.back() })
            }
        }
    }
}
