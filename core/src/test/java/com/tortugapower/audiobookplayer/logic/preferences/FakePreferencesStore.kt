package com.tortugapower.audiobookplayer.logic.preferences

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [PreferencesStore] for unit tests — emits on every mutation like the real DataStore. */
class FakePreferencesStore(initial: Map<String, String> = emptyMap()) : PreferencesStore {
    private val state = MutableStateFlow(initial.toMap())

    override suspend fun getString(key: String): String? = state.value[key]

    override suspend fun setString(key: String, value: String) {
        state.value = state.value + (key to value)
    }

    override suspend fun remove(key: String) {
        state.value = state.value - key
    }

    override suspend fun keysWithPrefix(prefix: String): Set<String> =
        state.value.keys.filter { it.startsWith(prefix) }.toSet()

    override suspend fun removeWithPrefix(prefix: String) {
        state.value = state.value.filterKeys { !it.startsWith(prefix) }
    }

    override fun observeString(key: String): Flow<String?> = state.map { it[key] }

    override fun observeAll(): Flow<Map<String, String>> = state
}
