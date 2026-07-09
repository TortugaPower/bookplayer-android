package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.database.entities.BookmarkEntity
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.datalayer.WatchAuthPayload
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.LibraryRepository
import com.tortugapower.audiobookplayer.wear.auth.WatchAuthenticator
import com.tortugapower.audiobookplayer.wear.auth.WearAuthOutcome
import com.tortugapower.audiobookplayer.wear.data.WearThemeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers `signIn()`'s outcome handling now that the authenticator is injected — the branch that
 * persists the transferred account (incl. the RevenueCat id) and the error mappings, none of which
 * needed a device to verify once [WatchAuthenticator] is a fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearRootViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeAccountRepository(private val failOnSave: Boolean = false) : AccountRepository {
        val flow = MutableStateFlow<AccountEntity?>(null)
        var saved: AccountEntity? = null
        override fun getAccountFlow(): Flow<AccountEntity?> = flow
        override suspend fun getAccount(): AccountEntity? = flow.value
        override suspend fun saveAccount(account: AccountEntity) {
            if (failOnSave) throw RuntimeException("disk/keystore failure")
            saved = account
            flow.value = account
        }
        override suspend fun deleteAccount() { saved = null; flow.value = null }
    }

    private class FakeAuthenticator(private val outcome: WearAuthOutcome) : WatchAuthenticator {
        override suspend fun requestAuth(): WearAuthOutcome = outcome
    }

    private class FakeThemeRepository : WearThemeRepository {
        override val theme: Flow<com.tortugapower.audiobookplayer.datalayer.WatchTheme?> = flowOf(null)
    }

    // Only getRootItems() is exercised (the VM's library-ready gate); the rest are unused stubs. The root
    // flow is injectable so a test can withhold its first emission (gate stays not-ready) then release it.
    private class FakeLibraryRepository(
        private val rootItems: Flow<List<LibraryItemEntity>> = flowOf(emptyList()),
    ) : LibraryRepository {
        override fun getRootItems(): Flow<List<LibraryItemEntity>> = rootItems
        override fun getItemsInPath(path: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun getItemsInPathSync(path: String): List<LibraryItemEntity> = emptyList()
        override suspend fun getItemById(uuid: String): LibraryItemEntity? = null
        override suspend fun getItemByPath(path: String): LibraryItemEntity? = null
        override fun getFoldersInPath(path: String?): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun getAllContainers(): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun searchBooks(query: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override fun searchAllBooks(query: String): Flow<List<LibraryItemEntity>> = flowOf(emptyList())
        override suspend fun isCloudSyncActive(): Boolean = false
        override suspend fun saveItem(item: LibraryItemEntity) {}
        override suspend fun updateItem(item: LibraryItemEntity) {}
        override suspend fun updateItemProgress(uuid: String, currentTime: Double, isFinished: Boolean) {}
        override suspend fun getDescendantBooks(item: LibraryItemEntity): List<LibraryItemEntity> = emptyList()
        override suspend fun deleteItemWithFile(context: android.content.Context, item: LibraryItemEntity) {}
        override suspend fun deleteItemsWithFiles(context: android.content.Context, items: List<LibraryItemEntity>) {}
        override suspend fun moveItems(context: android.content.Context, items: List<LibraryItemEntity>, targetFolderPath: String?) {}
        override suspend fun combineToVolume(context: android.content.Context, items: List<LibraryItemEntity>, volumeName: String) {}
        override suspend fun convertVolumesToFolders(items: List<LibraryItemEntity>) {}
        override suspend fun convertFoldersToVolumes(context: android.content.Context, items: List<LibraryItemEntity>) {}
        override suspend fun reorderItems(items: List<LibraryItemEntity>) {}
        override suspend fun updateArtworkSync(item: LibraryItemEntity) {}
        override fun getBookmarksForBook(bookUuid: String): Flow<List<BookmarkEntity>> = flowOf(emptyList())
        override suspend fun getBookmarkAtTime(bookUuid: String, time: Double): BookmarkEntity? = null
        override suspend fun addBookmark(bookmark: BookmarkEntity): Long = 0L
        override suspend fun updateBookmark(bookmark: BookmarkEntity) {}
        override suspend fun deleteBookmark(bookmark: BookmarkEntity) {}
        override fun getChaptersForBook(bookUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>> = flowOf(emptyList())
        override suspend fun insertChapters(chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) {}
        override suspend fun replaceChaptersForBook(bookUuid: String, chapters: List<com.tortugapower.audiobookplayer.database.entities.ChapterEntity>) {}
        override suspend fun getAdjacentItem(currentItemUuid: String, next: Boolean): LibraryItemEntity? = null
        override suspend fun getExternalResource(itemUuid: String, provider: String): com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity? = null
        override fun getExternalResourcesForBook(itemUuid: String): Flow<List<com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity>> = flowOf(emptyList())
        override suspend fun saveExternalResource(externalResource: com.tortugapower.audiobookplayer.database.entities.ExternalResourceEntity) {}
        override suspend fun deleteExternalResource(itemUuid: String, provider: String) {}
        override suspend fun resolveStreamingUrl(item: LibraryItemEntity): LibraryItemEntity = item
        override suspend fun resolveStreamingUrls(items: List<LibraryItemEntity>): List<LibraryItemEntity> = items
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun modelFor(repo: AccountRepository, outcome: WearAuthOutcome) =
        WearRootViewModel(repo, FakeAuthenticator(outcome), FakeThemeRepository(), FakeLibraryRepository())

    @Test fun signIn_success_persistsTransferredAccountIncludingRevenuecatId() = runTest(dispatcher) {
        val repo = FakeAccountRepository()
        val payload = WatchAuthPayload("acc-1", "e@x.com", "jwt-1", AccountTier.PRO, revenuecatId = "rc-1")
        val model = modelFor(repo, WearAuthOutcome.Success(payload))

        model.signIn()
        advanceUntilIdle()

        val saved = repo.saved
        assertEquals("acc-1", saved?.id)
        assertEquals("e@x.com", saved?.email)
        assertEquals("jwt-1", saved?.apiToken)
        assertEquals(AccountTier.PRO, saved?.tier)
        assertEquals("rc-1", saved?.revenuecatId)
        assertEquals(SignInUiState.Idle, model.signInState.value)
    }

    @Test fun signIn_success_butPersistFails_setsErrorAndIsRecoverable() = runTest(dispatcher) {
        val repo = FakeAccountRepository(failOnSave = true)
        val payload = WatchAuthPayload("acc-1", "e@x.com", "jwt-1", AccountTier.PRO, revenuecatId = "rc-1")
        val model = modelFor(repo, WearAuthOutcome.Success(payload))

        model.signIn()
        advanceUntilIdle()

        // Not stuck on Loading — resolves to a retryable error instead of a permanent spinner.
        assertEquals(SignInUiState.Error(SignInError.FAILED), model.signInState.value)
    }

    @Test fun signIn_notSignedInOnPhone_setsErrorAndDoesNotSave() = runTest(dispatcher) {
        val repo = FakeAccountRepository()
        val model = modelFor(repo, WearAuthOutcome.NotSignedInOnPhone)

        model.signIn()
        advanceUntilIdle()

        assertNull(repo.saved)
        assertEquals(SignInUiState.Error(SignInError.PHONE_NOT_SIGNED_IN), model.signInState.value)
    }

    @Test fun signIn_phoneNotReachable_setsError() = runTest(dispatcher) {
        val model = modelFor(FakeAccountRepository(), WearAuthOutcome.PhoneNotReachable)

        model.signIn()
        advanceUntilIdle()

        assertEquals(SignInUiState.Error(SignInError.PHONE_NOT_REACHABLE), model.signInState.value)
    }

    @Test fun signIn_failed_setsError() = runTest(dispatcher) {
        val model = modelFor(FakeAccountRepository(), WearAuthOutcome.Failed("boom"))

        model.signIn()
        advanceUntilIdle()

        assertEquals(SignInUiState.Error(SignInError.FAILED), model.signInState.value)
    }

    // Cold-start gate: `mode` starts null (unresolved) and `isReady` starts false, so the root holds the
    // loading screen instead of flashing the wrong mode before the first local DB read.
    @Test fun gate_initialState_modeNullAndNotReady() = runTest(dispatcher) {
        val model = modelFor(FakeAccountRepository(), WearAuthOutcome.Failed("unused"))

        assertNull(model.mode.value)
        assertFalse(model.isReady.value)
    }

    // Signed-out (remote) watch: once the account resolves, mode settles to REMOTE_CONTROLLER and the gate
    // opens immediately — remote mode never waits on the standalone library.
    @Test fun gate_signedOut_readyOnceAccountResolves() = runTest(dispatcher) {
        val model = modelFor(FakeAccountRepository(), WearAuthOutcome.Failed("unused"))

        backgroundScope.launch { model.isReady.collect {} }
        advanceUntilIdle()

        assertEquals(WatchMode.REMOTE_CONTROLLER, model.mode.value)
        assertTrue(model.isReady.value)
    }

    // PRO (standalone) watch: the gate stays closed until the library's first load emits, even after the
    // account has resolved to STANDALONE — guards the standalone empty-state flash.
    @Test fun gate_standalone_waitsForLibraryFirstLoad() = runTest(dispatcher) {
        val rootItems = MutableSharedFlow<List<LibraryItemEntity>>(replay = 1)
        val repo = FakeAccountRepository()
        repo.flow.value = AccountEntity(
            id = "acc-1", email = "e@x.com", apiToken = "jwt-1", tier = AccountTier.PRO, revenuecatId = null,
        )
        val model = WearRootViewModel(
            repo, FakeAuthenticator(WearAuthOutcome.Failed("unused")), FakeThemeRepository(),
            FakeLibraryRepository(rootItems),
        )

        backgroundScope.launch { model.isReady.collect {} }
        advanceUntilIdle()
        // Account resolved to standalone, but the library hasn't emitted → still gated.
        assertEquals(WatchMode.STANDALONE, model.mode.value)
        assertFalse(model.isReady.value)

        rootItems.emit(emptyList())
        advanceUntilIdle()
        assertTrue(model.isReady.value)
    }

    // Delete-downloads gating: enabled iff the Processed folder holds bytes (boundary at 0).
    @Test fun hasDownloads_zeroBytes_isFalse() =
        assertFalse(WearRootViewModel.hasDownloads(0L))

    @Test fun hasDownloads_someBytes_isTrue() =
        assertTrue(WearRootViewModel.hasDownloads(1L))
}
