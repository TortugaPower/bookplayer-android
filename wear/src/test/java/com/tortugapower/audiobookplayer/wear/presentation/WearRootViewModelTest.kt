package com.tortugapower.audiobookplayer.wear.presentation

import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.datalayer.WatchAuthPayload
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.wear.auth.WatchAuthenticator
import com.tortugapower.audiobookplayer.wear.auth.WearAuthOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private class FakeAccountRepository : AccountRepository {
        val flow = MutableStateFlow<AccountEntity?>(null)
        var saved: AccountEntity? = null
        override fun getAccountFlow(): Flow<AccountEntity?> = flow
        override suspend fun getAccount(): AccountEntity? = flow.value
        override suspend fun saveAccount(account: AccountEntity) { saved = account; flow.value = account }
        override suspend fun deleteAccount() { saved = null; flow.value = null }
    }

    private class FakeAuthenticator(private val outcome: WearAuthOutcome) : WatchAuthenticator {
        override suspend fun requestAuth(): WearAuthOutcome = outcome
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun modelFor(repo: AccountRepository, outcome: WearAuthOutcome) =
        WearRootViewModel(repo, FakeAuthenticator(outcome))

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
}
