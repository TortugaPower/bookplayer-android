package com.tortugapower.audiobookplayer.logic.preferences

import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.LibraryLayout
import com.tortugapower.audiobookplayer.logic.SyncTaskFactory
import com.tortugapower.audiobookplayer.repository.AccountRepository
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Library tab's layout and display toggles (the Options sheet), synced across devices as iOS syncs them
 * (`PreferencesSyncService`): [PreferenceFamily.Layout] and [PreferenceFamily.Display]. A change is written here
 * and, for a subscribed account, queued for the server like a sort rule (`LibrarySortManager`); a pull writes
 * the same keys, so the screen follows either.
 */
class LibraryDisplayPreferences(
    private val store: PreferencesStore,
    private val syncTaskRepository: SyncTaskRepository,
    private val accountRepository: AccountRepository,
) {

    fun observeLayout(): Flow<LibraryLayout> =
        store.observeString(PreferenceFamily.Layout.KEY).map(LibraryLayout::fromRawValue)

    suspend fun setLayout(layout: LibraryLayout) = set(PreferenceFamily.Layout.KEY, layout.rawValue)

    fun observeShowProgressAsPercentage(): Flow<Boolean> = observeToggle(PreferenceFamily.Display.PROGRESS_STYLE)

    suspend fun setShowProgressAsPercentage(enabled: Boolean) =
        set(PreferenceFamily.Display.PROGRESS_STYLE, enabled.toString())

    fun observeShowOriginalFileName(): Flow<Boolean> = observeToggle(PreferenceFamily.Display.TITLE_SOURCE)

    suspend fun setShowOriginalFileName(enabled: Boolean) =
        set(PreferenceFamily.Display.TITLE_SOURCE, enabled.toString())

    /** Sign-out: the layout and toggles go back to their defaults (iOS wipes every synced family). */
    suspend fun clearLocal() {
        store.removeWithPrefix(PreferenceFamily.Layout.prefix)
        store.removeWithPrefix(PreferenceFamily.Display.prefix)
    }

    // Off until turned on
    private fun observeToggle(key: String): Flow<Boolean> = store.observeString(key).map { it == true.toString() }

    private suspend fun set(key: String, value: String) {
        store.setString(key, value)
        val tier = accountRepository.getAccount()?.tier
        if (tier == AccountTier.PRO || tier == AccountTier.LITE) {
            SyncTaskFactory.createUploadPreferenceTask(syncTaskRepository, key, value)
        }
    }
}
