package com.tortugapower.audiobookplayer.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountDaoTest {

    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun updateTier_changesOnlyTheTier() = runBlocking {
        val account = AccountEntity(id = "1", email = "a@b.c", apiToken = "token", tier = AccountTier.PRO, revenuecatId = "rc")
        db.accountDao().saveAccount(account)

        assertEquals(1, db.accountDao().updateTier(AccountTier.LITE))
        assertEquals(account.copy(tier = AccountTier.LITE), db.accountDao().getAccount())
    }

    /** A tier read after a sign-out doesn't bring the account back */
    @Test fun updateTier_afterASignOut_writesNothing() = runBlocking {
        db.accountDao().saveAccount(AccountEntity(id = "1", email = "a@b.c", apiToken = "token", tier = AccountTier.PRO))
        db.accountDao().deleteAccount()

        assertEquals(0, db.accountDao().updateTier(AccountTier.FREE))
        assertNull(db.accountDao().getAccount())
    }
}
