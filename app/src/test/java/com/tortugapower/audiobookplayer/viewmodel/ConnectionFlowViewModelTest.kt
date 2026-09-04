package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.core.R as CoreR
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.JellyfinQuickConnect
import com.tortugapower.audiobookplayer.logic.ServerAddress
import com.tortugapower.audiobookplayer.network.AlternativeSignIn
import com.tortugapower.audiobookplayer.network.ConnectionError
import com.tortugapower.audiobookplayer.network.ConnectionResult
import com.tortugapower.audiobookplayer.network.ExternalLibraryInfo
import com.tortugapower.audiobookplayer.network.ExternalService
import com.tortugapower.audiobookplayer.network.LibraryResult
import com.tortugapower.audiobookplayer.network.PendingServer
import com.tortugapower.audiobookplayer.network.ProbeResult
import com.tortugapower.audiobookplayer.network.QuickConnectCapable
import com.tortugapower.audiobookplayer.network.ServerCapabilities
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import com.tortugapower.audiobookplayer.ui.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The flow's decisions, driven end to end against a fake server and a real in-memory Room: what
 * Connect lands on, what a failed Connect leaves behind, that credentials only go to the probed
 * address, that sign-in persists and ends the flow, and that re-auth prefills and updates in place.
 * Mirrors the iOS connection view-model tests (routing matrix, reauth, cancel clears the path).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConnectionFlowViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: ExternalServerRepository
    private lateinit var service: FakeService
    private val revoked = mutableListOf<ExternalServerEntity>()

    private object PlainCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(stored: String) = stored
    }

    /** A media server that answers whatever the test loaded into it; records what sign-in was asked. */
    private class FakeService : ExternalService, QuickConnectCapable {
        var probeResult: ProbeResult = ProbeResult.Found(pending())
        var connectResult: ConnectionResult = ConnectionResult.Success(token = "tok", name = "Home", stableId = "srv-1", userId = "u1")
        var probeGate: CompletableDeferred<Unit>? = null
        val connectCalls = mutableListOf<Triple<String, String?, String?>>()

        /** Quick Connect: the poller's transport, and the exchange the approved secret runs through. */
        val quickConnectTransport = FakeTransport()
        var quickConnectResult: ConnectionResult = ConnectionResult.Success(token = "qc-tok", name = "Home", stableId = "srv-1", userId = "u1", userName = "hana")
        var quickConnectGate: CompletableDeferred<Unit>? = null
        val quickConnectSecrets = mutableListOf<String>()

        override fun quickConnect(url: String, headers: Map<String, String>?) = JellyfinQuickConnect(quickConnectTransport, pollIntervalMs = 5_000, maxPolls = 3)

        override suspend fun signInWithQuickConnect(url: String, secret: String, headers: Map<String, String>?): ConnectionResult {
            quickConnectSecrets += secret
            quickConnectGate?.await()
            return quickConnectResult
        }

        override suspend fun probe(url: String, headers: Map<String, String>?): ProbeResult {
            probeGate?.await()
            return probeResult
        }

        override suspend fun connect(url: String, username: String?, password: String?, headers: Map<String, String>?): ConnectionResult {
            connectCalls += Triple(url, username, password)
            return connectResult
        }

        override suspend fun getLibraries(url: String, token: String, headers: Map<String, String>?): List<ExternalLibraryInfo> = error("unused")
        override suspend fun getLibrary(url: String, token: String, startIndex: Int, limit: Int, headers: Map<String, String>?, libraryId: String?): LibraryResult = error("unused")
        override suspend fun getStreamUrl(url: String, token: String, item: LibraryItemEntity): String = error("unused")
        override suspend fun getThumbnailUrl(url: String, token: String, item: LibraryItemEntity): String? = error("unused")
        override suspend fun revokeToken(url: String, token: String, headers: Map<String, String>?) = Unit

        companion object {
            fun pending(
                url: String = "https://abs.example.com",
                capabilities: ServerCapabilities = ServerCapabilities(),
            ) = PendingServer(url = url, serverName = "Home", stableId = null, capabilities = capabilities)
        }
    }

    private class FakeTransport : JellyfinQuickConnect.Transport {
        var ticket: JellyfinQuickConnect.Ticket? = JellyfinQuickConnect.Ticket("s3cr3t", "7H2K9Q")
        var approvedAfterPolls = Int.MAX_VALUE
        var polls = 0
        override suspend fun initiate() = ticket
        override suspend fun isAuthorized(secret: String): Boolean { polls++; return polls > approvedAfterPolls }
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Everything stays on the test scheduler: Room's executors run inline and the repository's
        // decryption hop uses the test dispatcher, so advanceUntilIdle() really means "done".
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = ExternalServerRepository(db.externalServerDao(), PlainCipher, dispatcher)
        service = FakeService()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel(
        type: ExternalServiceType = ExternalServiceType.AUDIOBOOKSHELF,
        mode: ConnectionFlowMode = ConnectionFlowMode.AddServer,
        ssoAvailable: Boolean = false,
    ) = ConnectionFlowViewModel(
        type = type,
        mode = mode,
        repository = repository,
        service = service,
        ssoAvailableOnDevice = { ssoAvailable },
        revokeStaleToken = { revoked += it },
    )

    /** A Jellyfin view model that has already probed a Quick-Connect-enabled server and landed on the method screen. */
    private fun TestScope.jellyfinOnMethodScreen(): Pair<ConnectionFlowViewModel, MutableList<ConnectionFlowEvent>> {
        service.probeResult = ProbeResult.Found(FakeService.pending(url = "http://jf.example.com:8096", capabilities = ServerCapabilities(quickConnectEnabled = true)))
        val vm = viewModel(type = ExternalServiceType.JELLYFIN)
        val events = eventsOf(vm)
        vm.onHostChanged("http://jf.example.com:8096")
        vm.connect()
        advanceUntilIdle()
        assertEquals(listOf<ConnectionFlowEvent>(ConnectionFlowEvent.NavigateTo(ConnectionFlowStep.METHOD)), events)
        assertEquals(AlternativeSignIn.QuickConnect, vm.uiState.value.alternativeSignIn)
        return vm to events
    }

    /** Collects the one-shot events on the test's background scope. */
    private fun TestScope.eventsOf(viewModel: ConnectionFlowViewModel): MutableList<ConnectionFlowEvent> {
        val events = mutableListOf<ConnectionFlowEvent>()
        // Eager collector, per the coroutines-test recipe for observing flows from a test.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        return events
    }

    private fun ConnectionFlowViewModel.typeAddress(host: String = "abs.example.com") {
        onSchemeChanged(ServerAddress.Scheme.HTTPS)
        onHostChanged(host)
    }

    private fun errorResId(vm: ConnectionFlowViewModel): Int? = (vm.uiState.value.error as? UiText.StringResource)?.resId

    // MARK: - Routing

    @Test fun `a password-only server routes straight to the password screen`() = runTest(dispatcher) {
        val vm = viewModel()
        val events = eventsOf(vm)
        vm.typeAddress()

        vm.connect()
        advanceUntilIdle()

        assertEquals(listOf<ConnectionFlowEvent>(ConnectionFlowEvent.NavigateTo(ConnectionFlowStep.PASSWORD)), events)
        assertNotNull(vm.uiState.value.pending)
        assertNull(vm.uiState.value.alternativeSignIn)
        assertFalse(vm.uiState.value.isLoading)
        assertNull(vm.uiState.value.error)
    }

    @Test fun `a jellyfin server with quick connect routes to the method screen with password still offered`() = runTest(dispatcher) {
        val (vm, _) = jellyfinOnMethodScreen()
        assertTrue(vm.uiState.value.supportsPassword)
        assertNull(vm.uiState.value.quickConnectStatus)
    }

    /** The dead-end config: SSO-only over plaintext. Connect fails with the reason, and no screen is pushed. */
    @Test fun `an sso-only server over http blocks connect on the address screen`() = runTest(dispatcher) {
        service.probeResult = ProbeResult.Found(FakeService.pending(url = "http://abs.example.com", capabilities = ServerCapabilities(supportsPassword = false, supportsOidc = true)))
        val vm = viewModel(ssoAvailable = true)
        val events = eventsOf(vm)
        vm.onHostChanged("http://abs.example.com")

        vm.connect()
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertNull(vm.uiState.value.pending)
        assertEquals(CoreR.string.media_servers_error_sso_requires_https, errorResId(vm))
    }

    @Test fun `an sso-only server without auth tab names the browser requirement`() = runTest(dispatcher) {
        service.probeResult = ProbeResult.Found(FakeService.pending(capabilities = ServerCapabilities(supportsPassword = false, supportsOidc = true)))
        val vm = viewModel(ssoAvailable = false)
        vm.typeAddress()

        vm.connect()
        advanceUntilIdle()

        assertEquals(CoreR.string.media_servers_error_sso_requires_chrome, errorResId(vm))
        assertNull(vm.uiState.value.pending)
    }

    @Test fun `a failed probe surfaces its error and pushes nothing`() = runTest(dispatcher) {
        service.probeResult = ProbeResult.Failure(ConnectionError.UnexpectedResponse(404))
        val vm = viewModel()
        val events = eventsOf(vm)
        vm.typeAddress()

        vm.connect()
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertNull(vm.uiState.value.pending)
        assertEquals(CoreR.string.media_servers_error_unexpected_response_with_code, errorResId(vm))
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun `connect is a no-op without an assemblable address`() = runTest(dispatcher) {
        val vm = viewModel()
        val events = eventsOf(vm)
        vm.onHostChanged("http://")
        assertFalse(vm.uiState.value.canConnect)

        vm.connect()
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        assertNull(vm.uiState.value.pending)
    }

    // MARK: - Sign-in

    @Test fun `sign-in goes to the probed address, persists the row and ends the flow`() = runTest(dispatcher) {
        val vm = viewModel()
        val events = eventsOf(vm)
        vm.typeAddress()
        vm.onHeaderAdded()
        vm.onHeaderChanged(vm.uiState.value.headers.single().id, "CF-Access-Client-Id", "abc")
        vm.connect()
        advanceUntilIdle()
        // An edit between Connect and Sign In must not redirect the credentials.
        vm.onHostChanged("evil.example.com")

        vm.onUsernameChanged("gianni")
        vm.onPasswordChanged("pw")
        vm.signIn()
        advanceUntilIdle()

        assertEquals(Triple("https://abs.example.com", "gianni", "pw"), service.connectCalls.single())
        val signedIn = events.filterIsInstance<ConnectionFlowEvent.SignedIn>().single()
        val stored = repository.allServers.first().single()
        assertEquals(stored.id, signedIn.server.id)
        assertEquals("https://abs.example.com", stored.url)
        assertEquals("Home", stored.name)
        assertEquals("tok", stored.token)
        assertEquals("u1", stored.userId)
        assertEquals("srv-1", stored.stableId)
        assertEquals(mapOf("CF-Access-Client-Id" to "abc"), stored.customHeaders)
        assertNull(vm.uiState.value.pending)
        assertEquals("", vm.uiState.value.password)
        assertTrue(revoked.isEmpty())
    }

    @Test fun `a wrong password keeps the pending server so a retry works`() = runTest(dispatcher) {
        val vm = viewModel()
        val events = eventsOf(vm)
        vm.typeAddress()
        vm.connect()
        advanceUntilIdle()
        vm.onUsernameChanged("gianni")
        vm.onPasswordChanged("wrong")

        service.connectResult = ConnectionError.Unauthorized.toFailure()
        vm.signIn()
        advanceUntilIdle()

        assertEquals(CoreR.string.media_servers_error_unauthorized, errorResId(vm))
        assertNotNull("the validated server is still good for another attempt", vm.uiState.value.pending)
        assertTrue(events.filterIsInstance<ConnectionFlowEvent.SignedIn>().isEmpty())

        service.connectResult = ConnectionResult.Success(token = "tok", name = "Home", userId = "u1")
        vm.onPasswordChanged("right")
        vm.signIn()
        advanceUntilIdle()
        assertEquals(1, events.filterIsInstance<ConnectionFlowEvent.SignedIn>().size)
        assertEquals(2, service.connectCalls.size)
    }

    // MARK: - Re-auth

    @Test fun `re-auth prefills from the saved row and updates it in place at a new address`() = runTest(dispatcher) {
        val id = repository.saveServer(
            ExternalServerEntity(
                name = "Home", type = ExternalServiceType.AUDIOBOOKSHELF, url = "https://old.example.com:8443/abs",
                username = "gianni", token = "stale", userId = "u1", selectedLibraryId = "lib-7",
                customHeaders = mapOf("X-Zed" to "1", "CF-Access-Client-Id" to "abc"),
            )
        )
        val saved = repository.getServerById(id)!!
        val vm = viewModel(mode = ConnectionFlowMode.Reauth(saved))
        val events = eventsOf(vm)

        val state = vm.uiState.value
        assertTrue(state.isReauth)
        assertEquals(ServerAddress.Scheme.HTTPS, state.address.scheme)
        assertEquals("old.example.com/abs", state.hostText)
        assertEquals("8443", state.portText)
        assertEquals("gianni", state.username)
        assertEquals("headers prefill sorted case-insensitively by key", listOf("CF-Access-Client-Id", "X-Zed"), state.headers.map { it.key })

        // The server moved host: the user edits the address before reconnecting.
        vm.onHostChanged("moved.example.com/abs")
        service.probeResult = ProbeResult.Found(FakeService.pending(url = "https://moved.example.com:8443/abs"))
        vm.connect()
        advanceUntilIdle()
        vm.onPasswordChanged("pw")
        service.connectResult = ConnectionResult.Success(token = "fresh", name = "Home", userId = "u1")
        vm.signIn()
        advanceUntilIdle()

        val rows = repository.allServers.first()
        assertEquals("the moved server must update its row, not fork", 1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals("https://moved.example.com:8443/abs", rows.single().url)
        assertEquals("fresh", rows.single().token)
        assertEquals("lib-7", rows.single().selectedLibraryId)
        assertEquals(listOf("stale"), revoked.map { it.token })
        assertEquals(id, events.filterIsInstance<ConnectionFlowEvent.SignedIn>().single().server.id)
    }

    // MARK: - Address field behavior

    @Test fun `a pasted URL redistributes across the fields`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onHostChanged("http://100.81.227.12:13378/abs")

        val state = vm.uiState.value
        assertEquals(ServerAddress.Scheme.HTTP, state.address.scheme)
        assertEquals("100.81.227.12/abs", state.hostText)
        assertEquals("13378", state.portText)
        assertEquals("http://100.81.227.12:13378/abs", state.url)
    }

    @Test fun `a scheme-less host with a port peels the port into its own field`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onHostChanged("jf.example.com:8096")
        assertEquals("jf.example.com", vm.uiState.value.hostText)
        assertEquals("8096", vm.uiState.value.portText)
        assertEquals("https://jf.example.com:8096", vm.uiState.value.url)
    }

    /** What the user types is never rewritten under the cursor; the model normalizes on its own. */
    @Test fun `typing is shown verbatim while the model normalizes`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onHostChanged("media.example.com/")
        assertEquals("media.example.com/", vm.uiState.value.hostText)
        assertEquals("https://media.example.com", vm.uiState.value.url)

        vm.onHostChanged("host:")
        assertEquals("host:", vm.uiState.value.hostText)
        assertFalse(vm.uiState.value.canConnect)

        vm.onHostChanged("host")
        vm.onPortChanged("80a96")
        assertEquals("only digits reach the port field", "8096", vm.uiState.value.portText)
        assertEquals(8096, vm.uiState.value.address.port)
        vm.onPortChanged("")
        assertNull(vm.uiState.value.address.port)
        assertEquals("https://host", vm.uiState.value.url)
    }

    @Test fun `headers are trimmed, blanks dropped and Authorization refused`() = runTest(dispatcher) {
        val vm = viewModel()
        repeat(4) { vm.onHeaderAdded() }
        val ids = vm.uiState.value.headers.map { it.id }
        vm.onHeaderChanged(ids[0], " CF-Access-Client-Id ", " abc ")
        vm.onHeaderChanged(ids[1], "", "orphan value")
        vm.onHeaderChanged(ids[2], "Authorization", "Bearer evil")
        vm.onHeaderChanged(ids[3], "X-Dup", "first")
        vm.onHeaderAdded()
        vm.onHeaderChanged(vm.uiState.value.headers.last().id, "X-Dup", "second")

        assertEquals(mapOf("CF-Access-Client-Id" to "abc", "X-Dup" to "second"), vm.headersMap())

        vm.onHeaderRemoved(ids[0])
        assertEquals(mapOf("X-Dup" to "second"), vm.headersMap())
    }

    // MARK: - Reset between presentations

    /** The view model outlives the sheet, so leaving the flow must forget the form — a reopened Add Server starts empty. */
    @Test fun `reset returns an add-server flow to an empty form`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onHostChanged("http://abs.example.com:13378/abs")
        vm.onHeaderAdded()
        vm.onHeaderChanged(vm.uiState.value.headers.single().id, "CF-Access-Client-Id", "abc")
        vm.onUsernameChanged("gianni")
        vm.onPasswordChanged("pw")
        vm.connect()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.pending)

        vm.reset()

        val state = vm.uiState.value
        assertEquals("", state.hostText)
        assertEquals("", state.portText)
        assertEquals(ServerAddress.Scheme.HTTPS, state.address.scheme)
        assertTrue(state.headers.isEmpty())
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertNull(state.pending)
        assertNull(state.route)
        assertNull(state.error)
        assertFalse(state.isLoading)
    }

    /** …while a re-auth flow re-prefills from the saved row it was opened for. */
    @Test fun `reset re-prefills a re-auth flow from the saved row`() = runTest(dispatcher) {
        val saved = ExternalServerEntity(
            id = 7, name = "Home", type = ExternalServiceType.AUDIOBOOKSHELF, url = "https://abs.example.com:8443/abs",
            username = "gianni", token = "stale", customHeaders = mapOf("X-One" to "1"),
        )
        val vm = viewModel(mode = ConnectionFlowMode.Reauth(saved))
        vm.onHostChanged("elsewhere.example.com")
        vm.onUsernameChanged("someone-else")
        vm.onHeaderRemoved(vm.uiState.value.headers.single().id)

        vm.reset()

        val state = vm.uiState.value
        assertEquals("abs.example.com/abs", state.hostText)
        assertEquals("8443", state.portText)
        assertEquals("gianni", state.username)
        assertEquals(listOf("X-One"), state.headers.map { it.key })
        assertTrue(state.isReauth)
    }

    // MARK: - Cancellation

    @Test fun `cancel stops an in-flight connect and leaves nothing behind`() = runTest(dispatcher) {
        service.probeGate = CompletableDeferred()
        val vm = viewModel()
        val events = eventsOf(vm)
        vm.typeAddress()

        vm.connect()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isLoading)

        vm.cancel()
        service.probeGate!!.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertTrue(events.isEmpty())
        assertNull(vm.uiState.value.pending)
    }

    // MARK: - Quick Connect

    @Test fun `quick connect signs in with the approved secret and ends the flow with the server's username`() = runTest(dispatcher) {
        val (vm, events) = jellyfinOnMethodScreen()
        service.quickConnectTransport.approvedAfterPolls = 1

        vm.startAlternativeSignIn()
        runCurrent()
        assertEquals(QuickConnectStatus.AwaitingCode("7H2K9Q"), vm.uiState.value.quickConnectStatus)
        assertEquals(1, service.quickConnectTransport.polls)

        advanceTimeBy(5_001)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("s3cr3t"), service.quickConnectSecrets)
        val signedIn = events.filterIsInstance<ConnectionFlowEvent.SignedIn>().single()
        val stored = repository.allServers.first().single()
        assertEquals(stored.id, signedIn.server.id)
        assertEquals("quick connect never asked for a username; the auth response supplies it", "hana", stored.username)
        assertEquals("qc-tok", stored.token)
        assertEquals("u1", stored.userId)
        assertEquals("http://jf.example.com:8096", stored.url)
        assertNull(vm.uiState.value.quickConnectStatus)
        assertNull(vm.uiState.value.pending)
    }

    @Test fun `quick connect failures map to copy and keep the pending server`() = runTest(dispatcher) {
        val (vm, events) = jellyfinOnMethodScreen()
        service.quickConnectTransport.ticket = null

        vm.startAlternativeSignIn()
        advanceUntilIdle()

        val failed = vm.uiState.value.quickConnectStatus as QuickConnectStatus.Failed
        assertEquals(R.string.media_servers_quick_connect_error_no_code, (failed.message as UiText.StringResource).resId)
        assertNotNull("the user can retry or fall back to the password without re-probing", vm.uiState.value.pending)
        assertTrue(events.filterIsInstance<ConnectionFlowEvent.SignedIn>().isEmpty())

        // OK dismisses the failure, and the flow can start again.
        vm.cancelQuickConnect()
        assertNull(vm.uiState.value.quickConnectStatus)
        service.quickConnectTransport.ticket = JellyfinQuickConnect.Ticket("s3cr3t", "7H2K9Q")
        vm.startAlternativeSignIn()
        runCurrent()
        assertEquals(QuickConnectStatus.AwaitingCode("7H2K9Q"), vm.uiState.value.quickConnectStatus)
    }

    @Test fun `quick connect times out with its own copy`() = runTest(dispatcher) {
        val (vm, _) = jellyfinOnMethodScreen()
        vm.startAlternativeSignIn()
        advanceUntilIdle()
        val failed = vm.uiState.value.quickConnectStatus as QuickConnectStatus.Failed
        assertEquals(R.string.media_servers_quick_connect_error_timeout, (failed.message as UiText.StringResource).resId)
    }

    @Test fun `a failed exchange shows the sign-in error inside the sheet`() = runTest(dispatcher) {
        val (vm, events) = jellyfinOnMethodScreen()
        service.quickConnectTransport.approvedAfterPolls = 0
        service.quickConnectResult = ConnectionError.Unauthorized.toFailure()

        vm.startAlternativeSignIn()
        advanceUntilIdle()

        val failed = vm.uiState.value.quickConnectStatus as QuickConnectStatus.Failed
        assertEquals(CoreR.string.media_servers_error_unauthorized, (failed.message as UiText.StringResource).resId)
        assertTrue(events.filterIsInstance<ConnectionFlowEvent.SignedIn>().isEmpty())
        assertTrue(repository.allServers.first().isEmpty())
    }

    @Test fun `cancelling quick connect while polling stops the poller`() = runTest(dispatcher) {
        val (vm, events) = jellyfinOnMethodScreen()
        vm.startAlternativeSignIn()
        runCurrent()
        val pollsAtCancel = service.quickConnectTransport.polls

        vm.cancelQuickConnect()
        advanceTimeBy(60_000)
        runCurrent()

        assertNull(vm.uiState.value.quickConnectStatus)
        assertEquals("no poll may land after cancel", pollsAtCancel, service.quickConnectTransport.polls)
        assertEquals(1, events.size)
    }

    @Test fun `cancelling during the exchange persists nothing`() = runTest(dispatcher) {
        val (vm, events) = jellyfinOnMethodScreen()
        service.quickConnectTransport.approvedAfterPolls = 0
        service.quickConnectGate = CompletableDeferred()

        vm.startAlternativeSignIn()
        runCurrent()
        assertEquals(QuickConnectStatus.Authenticating, vm.uiState.value.quickConnectStatus)

        vm.cancelQuickConnect()
        service.quickConnectGate!!.complete(Unit)
        advanceUntilIdle()

        assertNull(vm.uiState.value.quickConnectStatus)
        assertTrue(events.filterIsInstance<ConnectionFlowEvent.SignedIn>().isEmpty())
        assertTrue(repository.allServers.first().isEmpty())
    }

    @Test fun `dismissing the flow tears quick connect down too`() = runTest(dispatcher) {
        val (vm, _) = jellyfinOnMethodScreen()
        vm.startAlternativeSignIn()
        runCurrent()
        val pollsAtCancel = service.quickConnectTransport.polls

        vm.cancel()
        advanceTimeBy(60_000)
        runCurrent()

        assertNull(vm.uiState.value.quickConnectStatus)
        assertEquals(pollsAtCancel, service.quickConnectTransport.polls)
    }

    @Test fun `starting quick connect twice does not start a second poller`() = runTest(dispatcher) {
        val (vm, _) = jellyfinOnMethodScreen()
        vm.startAlternativeSignIn()
        vm.startAlternativeSignIn()
        runCurrent()
        assertEquals(1, service.quickConnectTransport.polls)
    }
}
