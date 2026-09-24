package io.github.superthom196.maa.ui

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.superthom196.maa.AppGraph
import io.github.superthom196.maa.R
import io.github.superthom196.maa.data.DiscoveredServer
import io.github.superthom196.maa.data.MaaInfo
import io.github.superthom196.maa.data.MaApiException
import io.github.superthom196.maa.data.NotLoggedInException
import io.github.superthom196.maa.data.syncPluginInfo
import io.github.superthom196.maa.data.OfflineException
import io.github.superthom196.maa.data.ServerConfig
import io.github.superthom196.maa.data.ServerInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Top-level screen: which of the three flows the app is in. */
enum class Phase { Connect, Login, Main }

/** Screens inside [Phase.Main]; the back stack is a plain list, the last entry is shown. */
enum class Screen { Home, Browse, Settings }

/** A server picked on the Connect screen (or the saved one whose token was rejected), awaiting sign-in. */
data class PendingServer(val baseUrl: String, val info: ServerInfo, val username: String? = null)

/** What we know about the server's MAA plugin (GET /maa/info). */
sealed interface PluginState {
    data object Checking : PluginState
    data class Found(val info: MaaInfo) : PluginState
    /** 404: plugin not installed or not enabled; nothing will play until it is. */
    data object Missing : PluginState
    /** Could not ask (offline, server error); [reason] is shown as is. */
    data class Unknown(val reason: String) : PluginState
}

data class UiState(
    val phase: Phase,
    val stack: List<Screen> = listOf(Screen.Home),
    // Connect
    val discovered: List<DiscoveredServer> = emptyList(),
    val discovering: Boolean = false,
    /** Base URL being validated with /info; blocks a second tap meanwhile. */
    val checkingUrl: String? = null,
    val connectError: String? = null,
    // Login
    val pending: PendingServer? = null,
    val loginBusy: Boolean = false,
    val loginError: String? = null,
    /** Why we are on the Login screen without the user asking (saved token rejected). */
    val loginNotice: String? = null,
    // Home / Settings
    val plugin: PluginState = PluginState.Checking,
    val urlsBusy: Boolean = false,
    val lanError: String? = null,
    val remoteError: String? = null,
    val urlsSaved: Boolean = false,
)

/**
 * The phone UI's state machine and the actions behind it. No server → Connect; a server whose
 * token the probe (GET /maa/info) rejects → Login; otherwise Main, shown at once so a phone
 * without network still opens to its settings while the probe runs behind it.
 *
 * Messages are resolved to strings here (AndroidViewModel) so screens just print them.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val config = AppGraph.config
    private val api = AppGraph.api

    val server: StateFlow<ServerConfig?> = config.server
    val settings = config.settings
    val currentBase: StateFlow<String?> = AppGraph.baseUrl.current
    val prefetch = AppGraph.audioCache.prefetch
    val cacheUsedBytes = AppGraph.audioCache.usedBytes

    private val _ui = MutableStateFlow(UiState(phase = if (config.server.value == null) Phase.Connect else Phase.Main))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var discoveryJob: Job? = null
    private var probeJob: Job? = null

    init {
        if (config.server.value == null) startDiscovery() else refreshPlugin()
        // Sign-out (here or anywhere else) empties the config: fall back to Connect. A config that
        // appears is always our own doing (login), which moves to Main itself.
        viewModelScope.launch {
            config.server.collect { cfg ->
                if (cfg == null && _ui.value.phase == Phase.Main) {
                    probeJob?.cancel()
                    _ui.value = UiState(phase = Phase.Connect)
                    startDiscovery()
                }
            }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    // ------------------------------------------------------------------ navigation

    fun navigate(screen: Screen) = _ui.update { it.copy(stack = it.stack + screen, urlsSaved = false, lanError = null, remoteError = null) }

    /** Pops the Main back stack; false when already at Home (the system then closes the app). */
    fun back(): Boolean {
        if (_ui.value.stack.size <= 1) return false
        _ui.update { it.copy(stack = it.stack.dropLast(1)) }
        return true
    }

    // ------------------------------------------------------------------ connect

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        _ui.update { it.copy(discovering = true, discovered = emptyList()) }
        val known = listOfNotNull(_ui.value.pending?.baseUrl) + config.server.value?.urls.orEmpty()
        discoveryJob = viewModelScope.launch {
            try {
                // discover() ends on its own after ~15 s; the timeout only guards a flow that never completes.
                withTimeoutOrNull(DISCOVERY_CAP_MS) {
                    AppGraph.discovery.discover(known.distinct()).collect { s ->
                        _ui.update { st ->
                            // mDNS and the subnet sweep find the same box twice: one row per server.
                            if (st.discovered.any { it.info.serverId == s.info.serverId }) st
                            else st.copy(discovered = st.discovered + s)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "discovery failed", e)
            } finally {
                _ui.update { it.copy(discovering = false) }
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
    }

    /** Manual entry: "192.168.1.50", "my-server:8095", "http://100.97.96.36:8095", ... */
    fun connectManual(text: String) {
        val base = ServerUrls.normalize(text)
        if (base == null) {
            _ui.update { it.copy(connectError = str(R.string.connect_bad_address)) }
            return
        }
        checkServer(base)
    }

    fun chooseServer(s: DiscoveredServer) = checkServer(s.baseUrl)

    /** Confirms a Music Assistant answers at [base] (discovery results can be stale), then asks for a login. */
    private fun checkServer(base: String) {
        if (_ui.value.checkingUrl != null) return
        _ui.update { it.copy(checkingUrl = base, connectError = null) }
        viewModelScope.launch {
            try {
                val info = api.fetchInfo(base, MANUAL_INFO_TIMEOUT_MS)
                stopDiscovery()
                val saved = config.server.value?.takeIf { it.serverId == info.serverId }
                _ui.update {
                    it.copy(
                        phase = Phase.Login, pending = PendingServer(base, info, saved?.username), checkingUrl = null,
                        loginError = null, loginNotice = null, loginBusy = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "no server at $base", e)
                _ui.update { it.copy(checkingUrl = null, connectError = str(R.string.connect_no_server, base, e.reason())) }
            }
        }
    }

    fun backToConnect() {
        _ui.update { it.copy(phase = Phase.Connect, pending = null, loginError = null, loginNotice = null, loginBusy = false) }
        startDiscovery()
    }

    // ------------------------------------------------------------------ login

    fun login(username: String, password: String) {
        val s = _ui.value.pending ?: return
        // The keyboard's Done and the button can both fire for one press; a second login would mint
        // a second long-lived token.
        if (_ui.value.loginBusy) return
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            _ui.update { it.copy(loginError = str(R.string.login_missing)) }
            return
        }
        _ui.update { it.copy(loginBusy = true, loginError = null) }
        viewModelScope.launch {
            try {
                val session = api.login(s.baseUrl, user, password)
                // Swap the ~30-day session token for a long-lived one so the car never silently logs
                // out; if the server refuses, the session token still gets us going.
                val token = try {
                    api.createLongLivedToken(s.baseUrl, session, "MAA (${Build.MODEL})")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "long-lived token refused, keeping the session token", e)
                    session
                }
                // Re-signing in to the same server keeps the address typed in Settings for the other slot.
                val prev = config.server.value?.takeIf { it.serverId == s.info.serverId }
                val (lan, remote) = ServerUrls.slotsForLogin(s.baseUrl, s.info.baseUrl)
                config.saveServer(
                    ServerConfig(
                        serverId = s.info.serverId,
                        name = s.info.name ?: prev?.name ?: str(R.string.default_server_name),
                        lanUrl = lan ?: prev?.lanUrl,
                        remoteUrl = remote ?: prev?.remoteUrl,
                        token = token,
                        username = user,
                        format = prev?.format ?: ServerConfig.DEFAULT_FORMAT,
                        pluginSeen = prev?.pluginSeen ?: false,
                    ),
                )
                _ui.update {
                    it.copy(phase = Phase.Main, stack = listOf(Screen.Home), pending = null, loginBusy = false, loginNotice = null, discovered = emptyList())
                }
                refreshPlugin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "login failed", e)
                val msg = when (Formatters.loginFailure(e)) {
                    LoginFailure.BAD_CREDENTIALS -> str(R.string.login_bad_credentials)
                    LoginFailure.UNREACHABLE -> str(R.string.login_unreachable, s.baseUrl)
                    LoginFailure.OTHER -> str(R.string.login_failed, e.reason())
                }
                _ui.update { it.copy(loginBusy = false, loginError = msg) }
            }
        }
    }

    // ------------------------------------------------------------------ plugin probe

    /**
     * Asks /maa/info. Doubles as the token probe: a rejected token sends the user to Login for the
     * saved server. On success the server's chosen format is copied into the config, which is what
     * every track URL requests.
     */
    fun refreshPlugin() {
        if (probeJob?.isActive == true) return
        _ui.update { it.copy(plugin = PluginState.Checking) }
        probeJob = viewModelScope.launch {
            val state = try {
                val info = api.syncPluginInfo(config)
                PluginState.Found(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotLoggedInException) {
                null
            } catch (e: MaApiException) {
                when {
                    e.code == 401 -> null
                    // A missing route answers 404 before any auth check, so it says nothing about the
                    // token: ask a plain library command before calling the plugin missing.
                    e.code == 404 && tokenRejected() -> null
                    e.code == 404 -> {
                        config.updateServer { if (it.pluginSeen) it.copy(pluginSeen = false) else it }
                        PluginState.Missing
                    }
                    else -> PluginState.Unknown(e.reason())
                }
            } catch (e: OfflineException) {
                PluginState.Unknown(str(R.string.plugin_offline))
            } catch (e: Exception) {
                Log.w(TAG, "maa/info failed", e)
                PluginState.Unknown(e.reason())
            }
            if (state == null) toLoginForSavedServer() else _ui.update { it.copy(plugin = state) }
        }
    }

    private suspend fun tokenRejected(): Boolean = try {
        api.libraryItems("playlists", offset = 0, limit = 1)
        false
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotLoggedInException) {
        true
    } catch (e: MaApiException) {
        e.code == 401
    } catch (e: Exception) {
        false
    }

    /** The saved token no longer works: sign in again at the same server rather than rediscovering it. */
    private suspend fun toLoginForSavedServer() {
        val cfg = config.server.value ?: return
        val base = currentBase.value ?: cfg.lanUrl ?: cfg.remoteUrl ?: return
        val info = try {
            api.fetchInfo(base)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServerInfo(serverId = cfg.serverId, name = cfg.name)
        }
        _ui.update {
            it.copy(
                phase = Phase.Login, pending = PendingServer(base, info, cfg.username),
                loginNotice = str(R.string.login_expired), loginError = null, loginBusy = false, plugin = PluginState.Checking,
            )
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * Saves the two addresses after checking each non-empty one reaches *this* server (same
     * server_id), so a typo or another Music Assistant on the network can never take over.
     */
    fun saveUrls(lanText: String, remoteText: String) {
        val cfg = config.server.value ?: return
        if (_ui.value.urlsBusy) return
        val lanIn = lanText.trim()
        val remoteIn = remoteText.trim()
        val lan = lanIn.takeIf { it.isNotEmpty() }?.let(ServerUrls::normalize)
        val remote = remoteIn.takeIf { it.isNotEmpty() }?.let(ServerUrls::normalize)
        var lanErr = if (lanIn.isNotEmpty() && lan == null) str(R.string.connect_bad_address) else null
        val remoteErr = if (remoteIn.isNotEmpty() && remote == null) str(R.string.connect_bad_address) else null
        if (lanIn.isEmpty() && remoteIn.isEmpty()) lanErr = str(R.string.settings_need_one_url)
        if (lanErr != null || remoteErr != null) {
            _ui.update { it.copy(lanError = lanErr, remoteError = remoteErr, urlsSaved = false) }
            return
        }
        _ui.update { it.copy(urlsBusy = true, lanError = null, remoteError = null, urlsSaved = false) }
        viewModelScope.launch {
            val lanCheck = lan?.let { verifySameServer(it, cfg.serverId) }
            val remoteCheck = remote?.let { verifySameServer(it, cfg.serverId) }
            if (lanCheck == null && remoteCheck == null) {
                config.updateServer { it.copy(lanUrl = lan, remoteUrl = remote) }
            }
            _ui.update { it.copy(urlsBusy = false, lanError = lanCheck, remoteError = remoteCheck, urlsSaved = lanCheck == null && remoteCheck == null) }
        }
    }

    /** Null when [url] answers as server [serverId], else the message to show under the field. */
    private suspend fun verifySameServer(url: String, serverId: String): String? = try {
        val info = api.fetchInfo(url, MANUAL_INFO_TIMEOUT_MS)
        if (info.serverId == serverId) null
        else str(R.string.settings_other_server, info.name ?: info.serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        str(R.string.connect_no_server, url, e.reason())
    }

    fun setLookahead(n: Int) = viewModelScope.launch {
        val v = n.coerceIn(Formatters.LOOKAHEAD_RANGE)
        if (settings.value.lookahead != v) config.saveSettings(settings.value.copy(lookahead = v))
    }

    fun setCacheMb(mb: Int) = viewModelScope.launch {
        if (settings.value.cacheMb != mb) config.saveSettings(settings.value.copy(cacheMb = mb))
    }

    fun clearCache() = viewModelScope.launch { AppGraph.audioCache.clear() }

    /** The config collector above moves the UI to Connect once the store is empty. */
    fun signOut() = viewModelScope.launch { config.signOut() }

    private fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        const val TAG = "MaaUi"
        const val DISCOVERY_CAP_MS = 20_000L
        /** Longer than fetchInfo's LAN default: a typed address may be a Tailscale hop away. */
        const val MANUAL_INFO_TIMEOUT_MS = 6_000L
    }
}
