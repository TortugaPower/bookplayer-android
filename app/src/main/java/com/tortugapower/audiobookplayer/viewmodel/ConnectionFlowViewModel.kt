package com.tortugapower.audiobookplayer.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.logic.ConnectionRouting
import com.tortugapower.audiobookplayer.logic.ExternalServerSaver
import com.tortugapower.audiobookplayer.logic.ExternalServiceUtils
import com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect
import com.tortugapower.audiobookplayer.logic.ServerAddress
import com.tortugapower.audiobookplayer.network.AlternativeSignIn
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalService
import com.tortugapower.audiobookplayer.network.ExternalServiceFactory
import com.tortugapower.audiobookplayer.network.PendingServer
import com.tortugapower.audiobookplayer.network.ProbeResult
import com.tortugapower.audiobookplayer.network.QuickConnectCapable
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.ui.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the connection flow was opened. Re-auth arrives prefilled from the saved row and replaces it on success. */
sealed class ConnectionFlowMode {
    data object AddServer : ConnectionFlowMode()
    data class Reauth(val server: ExternalServerEntity) : ConnectionFlowMode()
}

/** The pushed screens after the address root. */
enum class ConnectionFlowStep(val route: String) {
    METHOD("method"), PASSWORD("password"), HEADERS("headers")
}

/** One-shot signals the sheet acts on: a push, or the end of the flow. */
sealed class ConnectionFlowEvent {
    data class NavigateTo(val step: ConnectionFlowStep) : ConnectionFlowEvent()
    data class SignedIn(val server: ExternalServerEntity) : ConnectionFlowEvent()
}

/** One editable custom-header row. Keyed by [id] so Compose can track rows as they're added and removed. */
data class HeaderEntry(val id: Long, val key: String = "", val value: String = "")

/**
 * Render state of an in-flight Quick Connect flow — what its sheet shows. Null when none is running.
 * Kept apart from [ConnectionFlowUiState.alternativeSignIn]: that is availability, decided once at
 * Connect; this mutates several times per flow.
 */
sealed class QuickConnectStatus {
    /** Initiate is in flight. Briefly visible while the round-trip completes. */
    data object RetrievingCode : QuickConnectStatus()
    /** The server returned a code and we're polling. The user must enter it on the server's web UI. */
    data class AwaitingCode(val code: String) : QuickConnectStatus()
    /** The user approved; the secret is being exchanged for a token. */
    data object Authenticating : QuickConnectStatus()
    /** The flow ended in a failure; [message] is ready for display. */
    data class Failed(val message: UiText) : QuickConnectStatus()
}

data class ConnectionFlowUiState(
    val type: ExternalServiceType,
    val isReauth: Boolean,
    val address: ServerAddress,
    /**
     * The host field's display text. Mirrors [ServerAddress.hostField] only when a decomposition
     * moved something out of the field (a pasted URL, a peeled port); ordinary typing is shown
     * verbatim so a slash or colon under the cursor is never rewritten mid-keystroke.
     */
    val hostText: String,
    /** The port as typed, so an emptied or half-typed field is representable. */
    val portText: String,
    val headers: List<HeaderEntry>,
    val username: String,
    val password: String,
    /** The validated server, held across Connect → sign-in so credentials only ever go where the probe went. */
    val pending: PendingServer? = null,
    val route: ConnectionRouting.Decision.Route? = null,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val quickConnectStatus: QuickConnectStatus? = null,
) {
    val url: String? get() = address.url
    val canConnect: Boolean get() = url != null && !isLoading
    val canSignIn: Boolean get() = pending != null && username.isNotBlank() && password.isNotEmpty() && !isLoading
    val serverName: String get() = pending?.serverName.orEmpty()
    /** The probed address without its scheme — the method screen's title. */
    val displayAddress: String get() = pending?.url?.let { ServerAddress.parse(it)?.displayAddress } ?: address.displayAddress
    val alternativeSignIn: AlternativeSignIn? get() = route?.alternativeSignIn
    val supportsPassword: Boolean get() = route?.supportsPassword ?: true
}

/**
 * Drives the add-server / re-auth flow: address → (method) → password or Quick Connect, mirroring
 * iOS's connection view models. Owns the routing decision (what Connect lands on) so it is plain
 * testable logic, and every in-flight network call and poller so leaving the sheet can cancel it — a
 * dismissed sheet must never persist a connection the user gave up on.
 */
class ConnectionFlowViewModel(
    val type: ExternalServiceType,
    private val mode: ConnectionFlowMode,
    private val repository: ExternalServerRepository,
    private val service: ExternalService = ExternalServiceFactory.getService(type),
    /** Whether this device can run the SSO browser leg (Chrome 137+ Auth Tab). Wired in the SSO phase; false until then. */
    private val ssoAvailableOnDevice: () -> Boolean = { false },
    private val revokeStaleToken: suspend (ExternalServerEntity) -> Unit = { stale ->
        stale.token?.let { ExternalServiceFactory.getService(stale.type).revokeToken(stale.url, it, stale.customHeaders) }
    },
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState(type, mode))
    val uiState: StateFlow<ConnectionFlowUiState> = _uiState.asStateFlow()

    private val _events = Channel<ConnectionFlowEvent>(Channel.BUFFERED)
    val events: Flow<ConnectionFlowEvent> = _events.receiveAsFlow()

    private var actionJob: Job? = null
    private var nextHeaderId = (_uiState.value.headers.maxOfOrNull { it.id } ?: 0L) + 1

    /** The active Quick Connect poller, its state subscription, and the final token exchange — all torn down together. */
    private var quickConnect: JellyfinQuickConnect? = null
    private var quickConnectStateJob: Job? = null
    private var quickConnectSignInJob: Job? = null

    // MARK: - Address

    fun onSchemeChanged(scheme: ServerAddress.Scheme) {
        _uiState.update { it.copy(address = it.address.withScheme(scheme)) }
    }

    fun onHostChanged(text: String) {
        _uiState.update { state ->
            val next = state.address.withHostField(text)
            // Echo the model back into the field only when a decomposition moved something OUT of it:
            // a pasted full URL (scheme + port redistribute), or a `host:port` whose port was peeled off.
            // Anything else (a trailing slash, a bare colon, brackets) stays exactly as typed.
            val decomposed = text.contains("://") && next.url != null
            val portPeeled = !text.contains("://") && next.port != state.address.port
            val hostText = if (decomposed || portPeeled) next.hostField else text
            state.copy(
                address = next,
                hostText = hostText,
                portText = if (decomposed || portPeeled) next.port?.toString().orEmpty() else state.portText,
            )
        }
    }

    fun onPortChanged(text: String) {
        val digits = text.filter { it.isDigit() }
        _uiState.update { it.copy(portText = digits, address = it.address.withPort(digits.toIntOrNull())) }
    }

    // MARK: - Headers

    fun onHeaderAdded() {
        val entry = HeaderEntry(id = nextHeaderId++)
        _uiState.update { it.copy(headers = it.headers + entry) }
    }

    fun onHeaderChanged(id: Long, key: String, value: String) {
        _uiState.update { state ->
            state.copy(headers = state.headers.map { if (it.id == id) it.copy(key = key, value = value) else it })
        }
    }

    fun onHeaderRemoved(id: Long) {
        _uiState.update { state -> state.copy(headers = state.headers.filterNot { it.id == id }) }
    }

    /** The headers as they'll be sent: trimmed, blanks dropped, later duplicates win, `Authorization` and illegal names/values dropped. */
    fun headersMap(): Map<String, String>? {
        val trimmed = _uiState.value.headers
            .map { it.key.trim() to it.value.trim() }
            .filter { (k, v) -> k.isNotEmpty() && v.isNotEmpty() }
            .toMap()
        return ExternalServiceUtils.sanitizeCustomHeaders(trimmed)?.takeIf { it.isNotEmpty() }
    }

    // MARK: - Credentials

    fun onUsernameChanged(text: String) = _uiState.update { it.copy(username = text) }
    fun onPasswordChanged(text: String) = _uiState.update { it.copy(password = text) }

    // MARK: - Actions

    /** Validates the address and asks the server which sign-in methods it offers, then routes. */
    fun connect() {
        val url = _uiState.value.address.url ?: return
        runAction {
            when (val result = service.probe(url, headersMap())) {
                is ProbeResult.Failure -> _uiState.update { it.copy(pending = null, route = null, error = result.error.toUiText()) }
                is ProbeResult.Found -> {
                    val isSecure = _uiState.value.address.scheme == ServerAddress.Scheme.HTTPS
                    when (val decision = ConnectionRouting.decide(type, result.server.capabilities, isSecure, ssoAvailableOnDevice())) {
                        is ConnectionRouting.Decision.Blocked ->
                            _uiState.update { it.copy(pending = null, route = null, error = decision.error.toUiText()) }
                        is ConnectionRouting.Decision.Route -> {
                            _uiState.update { it.copy(pending = result.server, route = decision) }
                            _events.send(
                                ConnectionFlowEvent.NavigateTo(
                                    if (decision.step == ConnectionRouting.Step.METHOD) ConnectionFlowStep.METHOD else ConnectionFlowStep.PASSWORD
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /** Password sign-in against the probed server; persists and ends the flow on success. */
    fun signIn() {
        val state = _uiState.value
        val pending = state.pending ?: return
        if (!state.canSignIn) return
        runAction {
            when (val result = service.connect(pending.url, state.username, state.password, headersMap())) {
                is ConnectionResult.Failure ->
                    // Keep `pending`: the validated server is still good for another attempt.
                    _uiState.update { it.copy(error = failureToUiText(result)) }
                is ConnectionResult.Success -> persistAndFinish(
                    result = result,
                    fallbackName = pending.serverName.ifBlank { state.address.host },
                    username = state.username,
                    url = pending.url,
                    stableId = result.stableId ?: pending.stableId,
                )
            }
        }
    }

    /** From the method screen: the password path is a push, not a modal. */
    fun goToPassword() {
        viewModelScope.launch { _events.send(ConnectionFlowEvent.NavigateTo(ConnectionFlowStep.PASSWORD)) }
    }

    fun goToHeaders() {
        viewModelScope.launch { _events.send(ConnectionFlowEvent.NavigateTo(ConnectionFlowStep.HEADERS)) }
    }

    /** Begins whichever alternative the method screen offers. Quick Connect and SSO present modally — they hand off to an external authority and come back. */
    fun startAlternativeSignIn() {
        when (_uiState.value.alternativeSignIn) {
            AlternativeSignIn.QuickConnect -> startQuickConnect()
            is AlternativeSignIn.Oidc -> Unit // SSO lands in the next phase.
            null -> Unit
        }
    }

    /** Stops any in-flight connect, sign-in or Quick Connect. Called when the sheet is dismissed so nothing persists afterwards. */
    fun cancel() {
        actionJob?.cancel()
        actionJob = null
        cancelQuickConnect()
        _uiState.update { it.copy(isLoading = false) }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    /**
     * Returns the flow to its starting point for the next presentation: Add Server empty, re-auth
     * re-prefilled from the saved row. The view model is keyed to the enclosing nav entry and outlives a
     * single opening of the sheet, so without this a reopened Add Server showed the last typed address
     * and headers. Called when the flow is left (dismissed, or finished) rather than when it is shown, so
     * a configuration change mid-typing keeps the user's input. Also tears down any Quick Connect in flight.
     */
    fun reset() {
        cancel()
        _uiState.value = initialState(type, mode)
    }

    // MARK: - Quick Connect

    /**
     * Starts the Quick Connect flow against the probed server. Idempotent while one is running; the
     * poller runs against the pending server's address, so no credentials exist yet.
     */
    private fun startQuickConnect() {
        if (quickConnect != null) return
        val pending = _uiState.value.pending ?: return
        val capable = service as? QuickConnectCapable ?: return
        val controller = capable.quickConnect(pending.url, headersMap())
        quickConnect = controller
        _uiState.update { it.copy(quickConnectStatus = QuickConnectStatus.RetrievingCode, error = null) }
        quickConnectStateJob = viewModelScope.launch {
            controller.state.collect { handleQuickConnectState(it, pending, capable) }
        }
        controller.start(viewModelScope)
    }

    /**
     * Cancels an in-flight Quick Connect and dismisses any failure status. Safe to call when none is
     * running. Cancels the token exchange too: without that there is a full network round-trip (the
     * `Authenticating` phase, during which the sheet still shows Cancel) that nothing could stop, and
     * it used to persist a connection the user had explicitly backed out of.
     */
    fun cancelQuickConnect() {
        quickConnectSignInJob?.cancel()
        quickConnectSignInJob = null
        teardownQuickConnect()
        _uiState.update { it.copy(quickConnectStatus = null) }
    }

    private fun handleQuickConnectState(state: JellyfinQuickConnect.State, pending: PendingServer, capable: QuickConnectCapable) {
        when (state) {
            // The StateFlow replays its initial Idle to every new subscriber (and stop() publishes one).
            // Mapping it to "no status" would tear the sheet down a hop after presenting it.
            JellyfinQuickConnect.State.Idle -> Unit
            JellyfinQuickConnect.State.RetrievingCode ->
                _uiState.update { it.copy(quickConnectStatus = QuickConnectStatus.RetrievingCode) }
            is JellyfinQuickConnect.State.AwaitingCode ->
                _uiState.update { it.copy(quickConnectStatus = QuickConnectStatus.AwaitingCode(state.code)) }
            is JellyfinQuickConnect.State.Authenticated -> {
                _uiState.update { it.copy(quickConnectStatus = QuickConnectStatus.Authenticating) }
                quickConnectSignInJob = viewModelScope.launch { completeQuickConnect(state.secret, pending, capable) }
            }
            is JellyfinQuickConnect.State.Failed -> {
                _uiState.update { it.copy(quickConnectStatus = QuickConnectStatus.Failed(quickConnectMessage(state.reason))) }
                teardownQuickConnect()
            }
        }
    }

    /** Exchanges the approved secret for a token, then ends the flow exactly like a password sign-in. */
    private suspend fun completeQuickConnect(secret: String, pending: PendingServer, capable: QuickConnectCapable) {
        when (val result = capable.signInWithQuickConnect(pending.url, secret, headersMap())) {
            is ConnectionResult.Failure -> {
                // Keep `pending` so the user can retry or fall back to the password without re-probing.
                _uiState.update { it.copy(quickConnectStatus = QuickConnectStatus.Failed(failureToUiText(result))) }
                teardownQuickConnect()
            }
            is ConnectionResult.Success -> {
                // Drop the sheet before the flow ends, so it never lingers over the hide animation.
                _uiState.update { it.copy(quickConnectStatus = null) }
                teardownQuickConnect()
                persistAndFinish(
                    result = result,
                    fallbackName = pending.serverName.ifBlank { _uiState.value.address.host },
                    // Quick Connect doesn't ask for a username up front; the auth response carries it.
                    username = result.userName ?: _uiState.value.username,
                    url = pending.url,
                    stableId = result.stableId ?: pending.stableId,
                )
            }
        }
    }

    /** Drops the poller and its subscription. Leaves the status alone so callers decide between dismissing and showing a failure. */
    private fun teardownQuickConnect() {
        // stop() unconditionally: releasing the reference is not enough to end a poll, and an
        // unstopped poller keeps hitting the server for its full ~16-minute budget with nobody listening.
        quickConnect?.stop()
        quickConnect = null
        quickConnectStateJob?.cancel()
        quickConnectStateJob = null
    }

    /** User-presentable copy per failure reason. Never the raw payload — the poller keeps that for logs. */
    private fun quickConnectMessage(reason: JellyfinQuickConnect.Failure): UiText = UiText.StringResource(
        when (reason) {
            JellyfinQuickConnect.Failure.TIMEOUT -> R.string.media_servers_quick_connect_error_timeout
            JellyfinQuickConnect.Failure.NO_CODE -> R.string.media_servers_quick_connect_error_no_code
            JellyfinQuickConnect.Failure.OTHER -> R.string.media_servers_quick_connect_error_generic
        }
    )

    // MARK: - Internals

    private fun runAction(block: suspend () -> Unit) {
        actionJob?.cancel()
        _uiState.update { it.copy(isLoading = true, error = null) }
        actionJob = viewModelScope.launch {
            try {
                block()
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun persistAndFinish(
        result: ConnectionResult.Success,
        fallbackName: String,
        username: String?,
        url: String,
        stableId: String?,
    ) {
        val saved = ExternalServerSaver.save(
            repository,
            ExternalServerSaver.SignIn(
                type = type,
                name = result.name ?: fallbackName,
                url = url,
                username = username,
                token = result.token,
                headers = headersMap(),
                stableId = stableId,
                userId = result.userId,
                replacingId = (mode as? ConnectionFlowMode.Reauth)?.server?.id,
            )
        )
        saved.staleTokenToRevoke?.let { stale ->
            // Best-effort and detached from the flow: the sheet is about to close.
            viewModelScope.launch { runCatching { revokeStaleToken(stale) } }
        }
        _uiState.update { it.copy(pending = null, route = null, password = "") }
        _events.send(ConnectionFlowEvent.SignedIn(saved.server))
    }

    private fun ConnectionError.toUiText(): UiText = UiText.StringResource(messageResId, *args.toTypedArray())

    companion object {
        private fun initialState(type: ExternalServiceType, mode: ConnectionFlowMode): ConnectionFlowUiState {
            val server = (mode as? ConnectionFlowMode.Reauth)?.server
            val address = server?.let { ServerAddress.parse(it.url) } ?: ServerAddress(ServerAddress.Scheme.HTTPS, "")
            // Prefilled headers sort case-insensitively by key, as the iOS form does.
            val headers = server?.customHeaders.orEmpty().entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
                .mapIndexed { index, (key, value) -> HeaderEntry(id = index + 1L, key = key, value = value) }
            return ConnectionFlowUiState(
                type = type,
                isReauth = server != null,
                address = address,
                hostText = address.hostField,
                portText = address.port?.toString().orEmpty(),
                headers = headers,
                username = server?.username.orEmpty(),
                password = "",
            )
        }
    }
}

private fun failureToUiText(failure: ConnectionResult.Failure): UiText =
    failure.messageResId?.let { UiText.StringResource(it, *(failure.args ?: emptyList()).toTypedArray()) }
        ?: UiText.DynamicString(failure.message)

class ConnectionFlowViewModelFactory(
    private val type: ExternalServiceType,
    private val mode: ConnectionFlowMode,
    private val repository: ExternalServerRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ConnectionFlowViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ConnectionFlowViewModel(type, mode, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
