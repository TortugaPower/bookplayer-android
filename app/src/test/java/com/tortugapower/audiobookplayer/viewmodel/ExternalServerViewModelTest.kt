package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ExternalServerEntity
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.repository.ExternalServerRepository
import com.tortugapower.audiobookplayer.repository.TokenCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renaming a saved connection: the name is the only field the details sheet may change, and only to a real one. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExternalServerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repository: ExternalServerRepository

    private object PlainCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(stored: String) = stored
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = ExternalServerRepository(db.externalServerDao(), PlainCipher, dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun saved(): ExternalServerEntity {
        val id = repository.saveServer(
            ExternalServerEntity(
                name = "Home", type = ExternalServiceType.AUDIOBOOKSHELF, url = "https://abs.example.com",
                username = "gianni", token = "tok", userId = "u1", selectedLibraryId = "lib-1",
                customHeaders = mapOf("CF-Access-Client-Id" to "abc"),
            )
        )
        return repository.getServerById(id)!!
    }

    @Test fun `rename persists the trimmed name and nothing else`() = runTest(dispatcher) {
        val server = saved()
        val vm = ExternalServerViewModel(repository)

        vm.renameServer(server, "  Living room  ")
        advanceUntilIdle()

        val updated = repository.getServerById(server.id)!!
        assertEquals("Living room", updated.name)
        assertEquals(server.copy(name = "Living room"), updated)
    }

    @Test fun `a blank or unchanged name is a no-op`() = runTest(dispatcher) {
        val server = saved()
        val vm = ExternalServerViewModel(repository)

        vm.renameServer(server, "   ")
        vm.renameServer(server, "Home")
        advanceUntilIdle()

        assertEquals(server, repository.getServerById(server.id))
    }
}
