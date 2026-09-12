package com.tortugapower.audiobookplayer.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Jellyfin's Quick Connect authorization flow: ask the server for a short user-facing code, then poll
 * until the user enters that code in an already-authenticated session of the server's web UI, and hand
 * back the authorized secret for the token exchange. Same cadence as the Jellyfin SDK helper iOS uses:
 * every 5 s, at most 200 times (~16 minutes) before giving up.
 *
 * Pure orchestration over a [Transport] so the state table is unit-tested with virtual time; the
 * Retrofit-backed transport lives in `JellyfinService`.
 */
class JellyfinQuickConnect(
    private val transport: Transport,
    private val pollIntervalMs: Long = 5_000,
    private val maxPolls: Int = 200,
) {
    init {
        require(pollIntervalMs > 0) { "Polling interval must be positive" }
        require(maxPolls > 0) { "Maximum polls must be positive" }
    }

    interface Transport {
        /** `POST /QuickConnect/Initiate`. Null when the server answered without a secret or code; throws on transport/HTTP errors. */
        suspend fun initiate(): Ticket?

        /** `GET /QuickConnect/Connect?secret=` → whether the user has approved. Throws on transport/HTTP errors (a 404 means the secret expired). */
        suspend fun isAuthorized(secret: String): Boolean
    }

    data class Ticket(val secret: String, val code: String)

    sealed class State {
        /** Not running. The initial value, and what [stop] resets to. */
        data object Idle : State()

        /** Initiate is in flight. Briefly visible while the round-trip completes. */
        data object RetrievingCode : State()

        /** The server returned a code and we're polling. The user must enter [code] on the server's web UI (User menu → Quick Connect). */
        data class AwaitingCode(val code: String) : State()

        /** The user approved. [secret] is what the token exchange needs. Terminal. */
        data class Authenticated(val secret: String) : State()

        /** The flow ended in a failure. Terminal. */
        data class Failed(val reason: Failure) : State()
    }

    enum class Failure {
        /** Initiate answered without a secret/code — usually Quick Connect is disabled on the server. */
        NO_CODE,
        /** The code was never entered within the polling budget. */
        TIMEOUT,
        /** A transport or HTTP error, typically a network failure. The raw cause is for logs, never the UI. */
        OTHER,
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts the flow on [scope]. No-op while one is already running or finished; [stop] first to rerun. */
    fun start(scope: CoroutineScope) {
        if (_state.value != State.Idle) return
        job = scope.launch { run() }
    }

    /** Stops the flow (user cancellation) and resets to [State.Idle]. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    private suspend fun run() {
        try {
            _state.value = State.RetrievingCode
            val ticket = transport.initiate()
            if (ticket == null) {
                _state.value = State.Failed(Failure.NO_CODE)
                return
            }
            _state.value = State.AwaitingCode(ticket.code)
            repeat(maxPolls) {
                if (transport.isAuthorized(ticket.secret)) {
                    _state.value = State.Authenticated(ticket.secret)
                    return
                }
                delay(pollIntervalMs)
            }
            _state.value = State.Failed(Failure.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(Failure.OTHER)
        }
    }
}
