package com.tortugapower.audiobookplayer.wear.presentation

import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.core.CoreContext
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.datalayer.WatchTheme
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.wear.auth.WatchAuthenticator
import com.tortugapower.audiobookplayer.wear.auth.WearAuthOutcome
import com.tortugapower.audiobookplayer.wear.data.WearThemeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Why a sign-in attempt failed, mapped to a localized message by the UI (never a raw exception string). */
enum class SignInError { PHONE_NOT_REACHABLE, PHONE_NOT_SIGNED_IN, FAILED }

sealed interface SignInUiState {
    data object Idle : SignInUiState
    data object Loading : SignInUiState
    data class Error(val error: SignInError) : SignInUiState
}

/**
 * The account read from the local DB. [Loading] is the pre-read state (distinct from a resolved `null` =
 * signed out) so the root can hold a loading screen instead of flashing the wrong mode on a cold start.
 */
private sealed interface AccountLoad {
    data object Loading : AccountLoad
    data class Loaded(val account: AccountEntity?) : AccountLoad
}

/**
 * Derives the current [WatchMode] from the account in the shared `:core` repository, and drives the
 * phone→watch sign-in handoff. On success it persists the transferred account (token Keystore-encrypted
 * by the repository) and re-checks the tier via RevenueCat — after which [mode] switches on its own.
 */
class WearRootViewModel(
    private val accountRepository: AccountRepository,
    private val authenticator: WatchAuthenticator,
    themeRepository: WearThemeRepository,
    libraryRepository: LibraryRepository,
) : ViewModel() {

    /** The user's phone-selected theme colors (null until the phone syncs one → default palette applies). */
    val theme: StateFlow<WatchTheme?> = themeRepository.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Single eager read of the account — the one source of truth for tier/mode. Eager (not WhileSubscribed)
    // so [mode] can't flash a default before the DB resolves, and so the sign-in observer's `accountLoad`
    // value is always current regardless of what's collecting. Seeded [Loading] to distinguish "not read
    // yet" (show the loading screen) from "signed out" (a resolved null → remote).
    private val accountLoad: StateFlow<AccountLoad> = accountRepository.getAccountFlow()
        .map<AccountEntity?, AccountLoad> { AccountLoad.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccountLoad.Loading)

    // null until the account is read from the DB (fast, local) — the root shows a loading screen while
    // unresolved, so a PRO watch doesn't flash the remote-controller UI before switching to standalone on
    // a cold start. Derived from [accountLoad] (the single source) so it can only resolve AFTER the account
    // does — the sign-in observer relies on that ordering.
    val mode: StateFlow<WatchMode?> = accountLoad
        .map { if (it is AccountLoad.Loaded) watchModeFor(it.account) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Standalone library first-load signal (parity with the phone) — true once the local root query emits.
    // Eager so it loads while the loading screen is up; local Room read, never waits on network sync.
    private val standaloneLibraryReady: StateFlow<Boolean> = libraryRepository.getRootItems()
        .map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Reveal the UI once the mode is resolved AND — for standalone — the library's first load is in, so the
     * standalone library never flashes its empty state on a cold start. Remote mode is gated only on the
     * mode resolving (its list comes from the phone over the Data Layer, which can be slow — we don't hold
     * the loading screen on it).
     */
    val isReady: StateFlow<Boolean> = combine(mode, standaloneLibraryReady) { m, libReady ->
        m != null && (m != WatchMode.STANDALONE || libReady)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The signed-in account (null = signed out) — the Settings screen shows its email when present. */
    val account: StateFlow<AccountEntity?> = accountLoad
        .map { (it as? AccountLoad.Loaded)?.account }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _signInState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val signInState: StateFlow<SignInUiState> = _signInState.asStateFlow()

    init {
        // Clear the sign-in spinner once `mode` settles after a successful sign-in. A PRO sign-in holds the
        // spinner across the account emit (see signIn) so Settings doesn't flash the profile before the nav
        // host swaps to the library; the mode emission that drives that swap also drops us back to Idle, so
        // the now-standalone Settings screen shows the profile instead of a stuck spinner. Guarded on a
        // present account so it never fires during the in-flight request (the account is still null then).
        viewModelScope.launch {
            mode.collect {
                if (_signInState.value == SignInUiState.Loading &&
                    (accountLoad.value as? AccountLoad.Loaded)?.account != null
                ) {
                    _signInState.value = SignInUiState.Idle
                }
            }
        }
    }

    // Recomputed (off-main) on subscribe + after a delete — folder size isn't reactive on its own.
    private val storageTrigger = MutableStateFlow(0)

    private val processedBytes: StateFlow<Long> = storageTrigger
        .map { computeProcessedBytes() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Total size of downloaded files (the Processed folder), human-formatted — mirrors iOS ProfileView. */
    val storageUsed: StateFlow<String> = processedBytes
        .map { Formatter.formatShortFileSize(CoreContext.appContext, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Whether there's anything to delete — the Delete-downloads button is disabled when the folder is empty. */
    val hasDownloads: StateFlow<Boolean> = processedBytes
        .map { hasDownloads(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Triggered by the "Sign in with phone" button (manual, mirroring iOS — never auto on launch). */
    fun signIn() {
        if (_signInState.value == SignInUiState.Loading) return
        _signInState.value = SignInUiState.Loading
        viewModelScope.launch {
            _signInState.value = when (val outcome = authenticator.requestAuth()) {
                is WearAuthOutcome.Success ->
                    // Wrapped so a persist/login failure (disk/Keystore) can't leave the state stuck on
                    // Loading — that would hide the button and block retry (a permanent spinner).
                    try {
                        val payload = outcome.payload
                        val entity = AccountEntity(
                            id = payload.accountId,
                            email = payload.email,
                            apiToken = payload.token,
                            tier = payload.tier,
                            revenuecatId = payload.revenuecatId,
                        )
                        accountRepository.saveAccount(entity)
                        // Authoritative tier re-check (mirrors iOS). Log in with the SAME RevenueCat user
                        // the phone uses (`revenuecatId ?: accountId`) — otherwise RevenueCat resolves a
                        // different user with no entitlement and downgrades the tier. No-ops on dev builds
                        // with an empty RevenueCat key; the phone-sent tier seeds the UI until RevenueCat
                        // resolves. Once the account is persisted, `mode` switches automatically.
                        SubscriptionManager.login(payload.revenuecatId ?: payload.accountId)
                        // A PRO sign-in flips the app to standalone; the `mode` StateFlow lags the account
                        // emit by a frame, so if we dropped to Idle here the Settings screen would flash the
                        // signed-in profile before the nav host swaps to the library. Hold the spinner
                        // instead — the screen unmounts on the swap (the init collector clears it if the
                        // account unexpectedly settles into remote mode). Non-PRO stays put → show profile.
                        if (watchModeFor(entity) == WatchMode.STANDALONE) {
                            SignInUiState.Loading
                        } else {
                            SignInUiState.Idle
                        }
                    } catch (e: Exception) {
                        SignInUiState.Error(SignInError.FAILED)
                    }
                WearAuthOutcome.PhoneNotReachable -> SignInUiState.Error(SignInError.PHONE_NOT_REACHABLE)
                WearAuthOutcome.NotSignedInOnPhone -> SignInUiState.Error(SignInError.PHONE_NOT_SIGNED_IN)
                is WearAuthOutcome.Failed -> SignInUiState.Error(SignInError.FAILED)
            }
        }
    }

    /** Delete all downloaded files (the Processed folder) and refresh the storage figure. */
    fun deleteDownloads() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { processedDir().deleteRecursively() }
            storageTrigger.value++
        }
    }

    /** Sign out: drop downloads, log out of RevenueCat, and clear the account (mode reverts to remote). */
    fun signOut() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { processedDir().deleteRecursively() }
            SubscriptionManager.logout()
            accountRepository.deleteAccount()
            storageTrigger.value++
        }
    }

    private fun processedDir(): File = File(CoreContext.appContext.filesDir, "Processed")

    private suspend fun computeProcessedBytes(): Long = withContext(Dispatchers.IO) {
        val dir = processedDir()
        if (dir.exists()) dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    companion object {
        /** Delete-downloads is enabled iff the Processed folder holds at least one byte (pure, unit-tested). */
        fun hasDownloads(bytes: Long): Boolean = bytes > 0L
    }
}
