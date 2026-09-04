package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.model.ExternalLibraryItem
import com.tortugapower.audiobookplayer.network.ExternalLibraryInfo
import com.tortugapower.audiobookplayer.network.LibraryResult
import com.tortugapower.audiobookplayer.network.SessionExpiredException
import com.tortugapower.audiobookplayer.repository.ExternalLibraryRepository
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import com.tortugapower.audiobookplayer.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The library screen's failure handling: a generic load failure lands in [ExternalLibraryViewModel.error]
 * (the Retry / Connection Details / Cancel alert), an expired session lands in
 * [ExternalLibraryViewModel.sessionExpiredServerName] instead (the Sign In alert), and Retry re-runs
 * exactly the step that failed — resolution while nothing is resolved, the page otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExternalLibraryViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var servers: ExternalServerRepository
    private val library = ScriptedLibraryRepository()
    private var serverId = 0L

    private object PlainCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(stored: String) = stored
    }

    /** Answers whatever the test loaded; a `throw` inside a script is the server failing. */
    private class ScriptedLibraryRepository : ExternalLibraryRepository() {
        var libraries: () -> List<ExternalLibraryInfo> = { listOf(ExternalLibraryInfo(id = "lib-1", name = "Audiobooks")) }
        var items: () -> LibraryResult = { LibraryResult(emptyList(), 0) }
        var librariesCalls = 0
        var itemsCalls = 0
        override suspend fun getLibraries(server: ExternalServerEntity): List<ExternalLibraryInfo> { librariesCalls++; return libraries() }
        override suspend fun getLibraryItems(server: ExternalServerEntity, startIndex: Int, limit: Int): LibraryResult { itemsCalls++; return items() }
    }

    @Before fun setUp() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        servers = ExternalServerRepository(db.externalServerDao(), PlainCipher, dispatcher)
        serverId = servers.saveServer(
            ExternalServerEntity(name = "Home", type = ExternalServiceType.AUDIOBOOKSHELF, url = "https://abs.example.com", username = "gianni", token = "tok")
        )
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = ExternalLibraryViewModel(serverId, servers, library)

    private fun item(id: String) = ExternalLibraryItem(LibraryItemEntity(uuid = id, title = id, type = ItemType.BOOK))

    private fun message(vm: ExternalLibraryViewModel) = (vm.error.value as? UiText.DynamicString)?.value

    @Test fun `a failed library fetch is a generic error that leaves the library unresolved`() = runTest(dispatcher) {
        library.libraries = { throw IllegalStateException("libraries down") }
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("libraries down", message(vm))
        assertNull(vm.resolvedLibraryId.value)
        assertNull("session expiry is a different alert", vm.sessionExpiredServerName.value)
        assertFalse("never the empty state on an error", vm.noLibraries.value)
        assertFalse(vm.isLoading.value)
    }

    @Test fun `retry while unresolved re-runs resolution`() = runTest(dispatcher) {
        library.libraries = { throw IllegalStateException("libraries down") }
        library.items = { LibraryResult(listOf(item("a")), 1) }
        val vm = viewModel()
        advanceUntilIdle()

        library.libraries = { listOf(ExternalLibraryInfo(id = "lib-1", name = "Audiobooks")) }
        vm.reload()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertEquals(2, library.librariesCalls)
        assertEquals("the single library resolves silently and pages", "lib-1", vm.resolvedLibraryId.value)
        assertEquals(listOf("a"), vm.items.value.map { it.entity.uuid })
    }

    @Test fun `a failed page keeps the library resolved and retry pages again`() = runTest(dispatcher) {
        library.items = { throw IllegalStateException("items down") }
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("items down", message(vm))
        assertEquals("lib-1", vm.resolvedLibraryId.value)
        assertTrue(vm.items.value.isEmpty())

        library.items = { LibraryResult(listOf(item("a"), item("b")), 2) }
        vm.reload()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertEquals("retry pages, it does not re-resolve", 1, library.librariesCalls)
        assertEquals(2, library.itemsCalls)
        assertEquals(listOf("a", "b"), vm.items.value.map { it.entity.uuid })
        assertTrue(vm.isLastPage.value)
    }

    @Test fun `an expired session is its own alert, not a generic error`() = runTest(dispatcher) {
        library.libraries = { throw SessionExpiredException() }
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("Home", vm.sessionExpiredServerName.value)
        assertNull(vm.error.value)
    }

    @Test fun `dismissing the error clears it without reloading`() = runTest(dispatcher) {
        library.items = { throw IllegalStateException("items down") }
        val vm = viewModel()
        advanceUntilIdle()

        vm.clearError()
        advanceUntilIdle()

        assertNull(vm.error.value)
        assertEquals(1, library.itemsCalls)
    }
}
