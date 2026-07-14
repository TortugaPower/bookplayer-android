package com.tortugapower.audiobookplayer.logic.preferences

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The preference sync channel (§5): a passive observer + sync coordinator over the LOCAL key-value
 * store, which is the single source of truth. It watches tracked keys, marks changed keys dirty,
 * batch-pushes them to the backend, and pulls the server's values (rate-limited) — applying each
 * through its owning [PreferenceFamily] (validate → write local → side effect).
 *
 * This is deliberately NOT the item-sync queue: preferences are user-scoped key/value pairs.
 *
 * @param alwaysTrackedKeys keys tracked from launch (e.g. the root sort key). Folder keys are
 *   tracked lazily via [registerKey] when their screen first appears.
 * @param pushDebounceMillis how long to coalesce a burst of local edits before pushing.
 */
class UserPreferencesSyncService(
    private val prefs: PreferencesStore,
    private val backend: PreferencesBackend,
    private val families: List<PreferenceFamily>,
    private val scope: CoroutineScope,
    private val alwaysTrackedKeys: Set<String> = emptySet(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val pushDebounceMillis: Long = DEFAULT_PUSH_DEBOUNCE_MILLIS,
    private val pullMinIntervalMillis: Long = PULL_MIN_INTERVAL_MILLIS,
) {
    private val mutex = Mutex()
    private val dirty = mutableSetOf<String>()
    private val registeredKeys = mutableSetOf<String>()
    private val lastKnown = mutableMapOf<String, String>()
    private var lastPullAt = 0L
    private var hasPulled = false
    private var seeded = false

    private var observeJob: Job? = null
    private var pushJob: Job? = null

    /**
     * Begin observing local changes and load the persisted dirty list (a kill mustn't lose queued
     * pushes). Also does an initial forced pull. Idempotent — safe to call on every launch/login.
     */
    suspend fun start() {
        val initial = prefs.observeAll().first()
        mutex.withLock {
            registeredKeys.addAll(alwaysTrackedKeys)
            if (dirty.isEmpty()) dirty.addAll(loadPersistedDirty())
            // Seed the baseline synchronously BEFORE observing, so a local change racing the observer
            // startup isn't mistaken for pre-existing state and silently skipped.
            lastKnown.clear()
            lastKnown.putAll(initial)
            seeded = true
        }
        if (observeJob?.isActive != true) {
            observeJob = scope.launch {
                prefs.observeAll().collect { snapshot -> onSnapshot(snapshot) }
            }
        }
        if (dirtySnapshot().isNotEmpty()) schedulePush()
        pull(force = true)
    }

    /**
     * Track a folder's key so its local changes start syncing. Called when a folder screen first
     * appears; idempotent.
     */
    suspend fun registerKey(key: String) {
        mutex.withLock { registeredKeys.add(key) }
    }

    private suspend fun onSnapshot(snapshot: Map<String, String>) {
        val toPush = mutex.withLock {
            if (!seeded) {
                // First emission just establishes a baseline — pre-existing values aren't "changes".
                lastKnown.clear()
                lastKnown.putAll(snapshot)
                seeded = true
                return@withLock false
            }
            var marked = false
            for ((key, value) in snapshot) {
                if (!isTracked(key)) continue
                if (lastKnown[key] != value) {
                    dirty.add(key)
                    marked = true
                }
            }
            lastKnown.clear()
            lastKnown.putAll(snapshot)
            marked
        }
        if (toPush) {
            persistDirty()
            schedulePush()
        }
    }

    /** A key is eligible for push when a family owns it AND it has been registered. */
    private fun isTracked(key: String): Boolean =
        key in registeredKeys && families.any { it.matches(key) }

    private fun schedulePush() {
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            delay(pushDebounceMillis)
            flushPush()
        }
    }

    /** Push all currently-dirty keys in one batch; clears them on success, keeps them on failure. */
    suspend fun flushPush() {
        val keys = dirtySnapshot()
        if (keys.isEmpty()) return
        val values = keys.mapNotNull { key -> prefs.getString(key)?.let { key to it } }.toMap()
        if (values.isEmpty()) {
            // Every dirty key was since removed locally — nothing to send.
            mutex.withLock { dirty.removeAll(keys) }
            persistDirty()
            return
        }
        try {
            backend.push(values)
            mutex.withLock { dirty.removeAll(values.keys) }
            persistDirty()
        } catch (e: Exception) {
            Log.w(TAG, "Preference push failed; keeping dirty for retry", e)
        }
    }

    /**
     * Pull the server's preferences and apply them locally. Successful pulls are rate-limited to one
     * per [pullMinIntervalMillis] unless [force] is set (launch/login/on-demand).
     */
    suspend fun pull(force: Boolean = false) {
        if (!force) {
            val (pulled, last) = mutex.withLock { hasPulled to lastPullAt }
            if (pulled && timeProvider() - last < pullMinIntervalMillis) return
        }
        val remote = try {
            backend.pull()
        } catch (e: Exception) {
            Log.w(TAG, "Preference pull failed", e)
            return
        }
        for ((key, rawValue) in remote) {
            val family = families.firstOrNull { it.matches(key) } ?: continue
            if (!family.isValid(key, rawValue)) {
                Log.w(TAG, "Rejecting invalid pulled preference $key=$rawValue")
                continue
            }
            applyRemote(family, key, rawValue)
        }
        mutex.withLock {
            lastPullAt = timeProvider()
            hasPulled = true
        }
    }

    private suspend fun applyRemote(family: PreferenceFamily, key: String, value: String) {
        // Suppress the echo: record the value as already-known BEFORE writing so the observer's
        // resulting emission is a no-op diff and doesn't mark this key dirty back at us. Also start
        // tracking any folder key the server knows about.
        mutex.withLock {
            lastKnown[key] = value
            registeredKeys.add(key)
        }
        prefs.setString(key, value)
        // Side effect (sort => resort that location's ranks locally; display prefs => none).
        family.onApplied(key, value)
    }

    /**
     * Logout: cancel pending pushes, drop the dirty list, remove observers, and delete every key
     * owned by a tracked family (all `library_sort:*`, etc.). The next login pulls fresh.
     */
    suspend fun onLogout() {
        observeJob?.cancel()
        pushJob?.cancel()
        mutex.withLock {
            dirty.clear()
            registeredKeys.clear()
            lastKnown.clear()
            lastPullAt = 0L
            hasPulled = false
            seeded = false
            observeJob = null
            pushJob = null
        }
        families.forEach { prefs.removeWithPrefix(it.keyPrefix) }
        prefs.remove(DIRTY_KEY)
    }

    private suspend fun dirtySnapshot(): Set<String> = mutex.withLock { dirty.toSet() }

    private suspend fun persistDirty() {
        prefs.setString(DIRTY_KEY, dirtySnapshot().joinToString(SEPARATOR))
    }

    private suspend fun loadPersistedDirty(): Set<String> =
        prefs.getString(DIRTY_KEY)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    companion object {
        private const val TAG = "PrefsSync"
        private const val DIRTY_KEY = "preferences_sync_dirty"
        private const val SEPARATOR = "\n"
        const val DEFAULT_PUSH_DEBOUNCE_MILLIS = 2_000L
        const val PULL_MIN_INTERVAL_MILLIS = 30_000L
    }
}
