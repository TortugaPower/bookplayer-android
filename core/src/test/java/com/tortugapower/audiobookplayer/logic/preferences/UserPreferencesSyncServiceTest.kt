package com.tortugapower.audiobookplayer.logic.preferences

import com.tortugapower.audiobookplayer.logic.sort.EffectiveSort
import com.tortugapower.audiobookplayer.logic.sort.SortLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UserPreferencesSyncServiceTest {

    private val ROOT_KEY = SortLocation.Root.storeKey

    private class FakeBackend(var remote: Map<String, String> = emptyMap()) : PreferencesBackend {
        val pushes = mutableListOf<Map<String, String>>()
        var pullCount = 0
        var failPush = false
        override suspend fun pull(): Map<String, String> {
            pullCount++
            return remote
        }
        override suspend fun push(values: Map<String, String>) {
            if (failPush) throw IOException("boom")
            pushes.add(values)
        }
    }

    private class RecordingSortFamily : PreferenceFamily {
        val applied = mutableListOf<Pair<String, String>>()
        override val keyPrefix = SortLocation.KEY_PREFIX
        override fun isValid(key: String, value: String) = EffectiveSort.isValidRawValue(value)
        override suspend fun onApplied(key: String, value: String) { applied.add(key to value) }
    }

    /** Build a service on an unconfined scope so its flow observer processes emissions eagerly. */
    private fun TestScope.service(
        prefs: PreferencesStore,
        backend: PreferencesBackend,
        family: PreferenceFamily = RecordingSortFamily(),
    ): Pair<UserPreferencesSyncService, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val svc = UserPreferencesSyncService(
            prefs, backend, listOf(family), scope,
            alwaysTrackedKeys = setOf(ROOT_KEY),
            timeProvider = { testScheduler.currentTime },
            pushDebounceMillis = 0,
        )
        return svc to scope
    }

    @Test fun `local change is marked dirty and batch-pushed`() = runTest {
        val prefs = FakePreferencesStore()
        val backend = FakeBackend()
        val (svc, scope) = service(prefs, backend)
        try {
            svc.start()
            advanceUntilIdle()

            prefs.setString(ROOT_KEY, "metadataTitle")
            advanceUntilIdle()

            assertTrue("expected a push carrying the changed key",
                backend.pushes.any { it[ROOT_KEY] == "metadataTitle" })
        } finally { scope.cancel() }
    }

    @Test fun `pull applies valid values, rejects unknown, runs side effect, and does not echo back`() = runTest {
        val prefs = FakePreferencesStore()
        val fam = RecordingSortFamily()
        val backend = FakeBackend(remote = mapOf(ROOT_KEY to "fileName", "library_sort:bad" to "garbage"))
        val (svc, scope) = service(prefs, backend, fam)
        try {
            svc.start()
            advanceUntilIdle()

            assertEquals("fileName", prefs.getString(ROOT_KEY))
            assertNull("unknown sort value must be rejected", prefs.getString("library_sort:bad"))
            assertTrue(fam.applied.contains(ROOT_KEY to "fileName"))
            // Applying a pulled value must NOT loop back into a push (echo suppression).
            assertTrue("applied pull must not be re-pushed", backend.pushes.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun `successful pulls are rate-limited to one per interval unless forced`() = runTest {
        val prefs = FakePreferencesStore()
        val backend = FakeBackend()
        val (svc, scope) = service(prefs, backend)
        try {
            svc.start() // does one forced pull
            advanceUntilIdle()
            val afterStart = backend.pullCount

            svc.pull(force = false) // within window -> skipped
            assertEquals(afterStart, backend.pullCount)

            advanceTimeBy(UserPreferencesSyncService.PULL_MIN_INTERVAL_MILLIS + 1)
            svc.pull(force = false) // window elapsed -> allowed
            assertEquals(afterStart + 1, backend.pullCount)

            svc.pull(force = true) // forced -> always
            assertEquals(afterStart + 2, backend.pullCount)
        } finally { scope.cancel() }
    }

    @Test fun `logout cancels pushes, drops dirty, and deletes all tracked keys`() = runTest {
        val prefs = FakePreferencesStore()
        val backend = FakeBackend().apply { failPush = true } // keep it dirty
        val (svc, scope) = service(prefs, backend)
        try {
            svc.start()
            advanceUntilIdle()
            prefs.setString(ROOT_KEY, "metadataTitle")
            prefs.setString("library_sort:folder-uuid", "mostRecent")
            advanceUntilIdle()

            svc.onLogout()
            advanceUntilIdle()

            assertNull(prefs.getString(ROOT_KEY))
            assertNull(prefs.getString("library_sort:folder-uuid"))
            assertNull(prefs.getString("preferences_sync_dirty"))
        } finally { scope.cancel() }
    }

    @Test fun `dirty list survives a restart and is pushed on next start`() = runTest {
        val prefs = FakePreferencesStore()
        // First run: push fails, so the dirty key is persisted.
        val backend1 = FakeBackend().apply { failPush = true }
        val (svc1, scope1) = service(prefs, backend1)
        try {
            svc1.start()
            advanceUntilIdle()
            prefs.setString(ROOT_KEY, "metadataTitle")
            advanceUntilIdle()
            assertTrue("dirty must be persisted after a failed push",
                prefs.getString("preferences_sync_dirty")?.contains(ROOT_KEY) == true)
        } finally { scope1.cancel() }

        // Second run ("after a kill"): a fresh service loads the persisted dirty and pushes it.
        val backend2 = FakeBackend()
        val (svc2, scope2) = service(prefs, backend2)
        try {
            svc2.start()
            advanceUntilIdle()
            assertTrue("persisted dirty key must be pushed on restart",
                backend2.pushes.any { it[ROOT_KEY] == "metadataTitle" })
        } finally { scope2.cancel() }
    }

    @Test fun `boolean family accepts 0 and 1 integers`() {
        val fam = BooleanPreferenceFamily("display_pref:")
        assertTrue(fam.isValid("display_pref:x", "1"))
        assertTrue(fam.isValid("display_pref:x", "0"))
        assertTrue(fam.isValid("display_pref:x", "true"))
        assertTrue(!fam.isValid("display_pref:x", "2"))
        assertEquals("true", PreferenceValueParsers.normalizeScalar(true))
        assertEquals("1", PreferenceValueParsers.normalizeScalar(1.0))
    }
}
