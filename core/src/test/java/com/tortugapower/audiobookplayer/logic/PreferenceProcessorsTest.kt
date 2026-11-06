package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.logic.preferences.PreferenceChanges
import com.tortugapower.audiobookplayer.logic.preferences.PreferenceFamily
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises the preference processors' real process() paths against a MockWebServer: the upload's
 * PATCH wire shape end-to-end, and the fetch's per-entry guards (pending-upload skip so a stale
 * pull can't revert a just-made local change, and invalid-value rejection).
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceProcessorsTest {

    companion object {
        private val server = MockWebServer()
        private lateinit var api: com.tortugapower.audiobookplayer.network.PreferencesApi

        @JvmStatic @BeforeClass fun startServer() {
            server.start()
            // Own Retrofit against the mock server — independent of the NetworkClient singleton,
            // whose BASE_URL other test classes may already have bound.
            api = retrofit2.Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
                .create(com.tortugapower.audiobookplayer.network.PreferencesApi::class.java)
        }

        @JvmStatic @AfterClass fun stopServer() = server.shutdown()
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun task(jobType: String, payload: String) = SyncTaskEntity(
        id = "t-$jobType", taskID = "t", queueKey = SyncTaskFactory.QUEUE_PREFERENCES,
        jobType = jobType, position = 0, payload = payload,
        status = SyncTaskStatus.PENDING, createdAt = 0L, errorMessage = null, attempts = 0,
    )

    private class FakeSyncTaskRepository(private val pendingUploadKeys: Set<String>) : SyncTaskRepository {
        override suspend fun getPendingTaskByTypeAndTaskId(jobType: String, taskId: String): SyncTaskEntity? =
            if (jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE && taskId in pendingUploadKeys) {
                SyncTaskEntity(
                    id = "pending", taskID = taskId, queueKey = SyncTaskFactory.QUEUE_PREFERENCES,
                    jobType = jobType, position = 0, payload = "{}",
                    status = SyncTaskStatus.PENDING, createdAt = 0L, errorMessage = null, attempts = 0,
                )
            } else null
        override suspend fun saveTask(task: SyncTaskEntity) = error("unused")
        override fun getAllTasks(): Flow<List<SyncTaskEntity>> = error("unused")
        override suspend fun getPendingTasks(): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksByStatus(status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getTasksInQueueByStatus(queueKey: String, status: SyncTaskStatus): List<SyncTaskEntity> = error("unused")
        override suspend fun getActiveQueueKeys(): List<String> = error("unused")
        override suspend fun updateTask(task: SyncTaskEntity) = error("unused")
        override suspend fun deleteTask(task: SyncTaskEntity) = error("unused")
        override suspend fun clearCompletedTasks() = error("unused")
        override suspend fun resetRunningTasks() = error("unused")
        override suspend fun deleteAllTasks() = error("unused")
        override suspend fun getTaskById(id: String): SyncTaskEntity? = error("unused")
        override suspend fun countActiveTasks(): Int = error("unused")
        override suspend fun countActiveTasksInQueue(queueKey: String): Int = error("unused")
        override suspend fun countActiveTasksByType(jobType: String): Int = error("unused")
        override suspend fun migrateTaskUuid(oldUuid: String, newUuid: String) = error("unused")
    }

    @Test fun `upload PATCHes the iOS wire shape`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val ok = PreferenceUploadProcessor(api).process(
            task(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, """{"key":"library_sort:default","value":"metadataTitle"}""")
        )
        assertTrue(ok)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/v1/user/preferences", recorded.path)
        assertEquals(
            """{"entries":[{"key":"library_sort:default","value":{"sort":"metadataTitle"}}]}""",
            recorded.body.readUtf8(),
        )
    }

    @Test fun `fetch writes valid entries, skips pending-upload keys, rejects invalid values`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"entries":[
                    {"key":"library_sort:default","value":{"sort":"metadataTitle"}},
                    {"key":"library_sort:pending","value":{"sort":"fileName"}},
                    {"key":"library_sort:bogus","value":{"sort":"notASort"}}
                ]}"""
            )
        )
        val store = DataStorePreferencesStore(context)
        store.setString("library_sort:pending", "mostRecent") // the local value a stale pull must not revert

        val ok = PreferenceFetchProcessor(context, FakeSyncTaskRepository(setOf("library_sort:pending")), api)
            .process(task(SyncTaskFactory.JOB_FETCH_PREFERENCES, "{}"))
        server.takeRequest()

        assertTrue(ok)
        assertEquals("metadataTitle", store.getString("library_sort:default"))
        // Pending-upload key kept the newer local value.
        assertEquals("mostRecent", store.getString("library_sort:pending"))
        // Invalid raw value rejected.
        assertNull(store.getString("library_sort:bogus"))
    }

    @Test fun `upload PATCHes the layout and a display toggle in their iOS shapes`() = runBlocking {
        val processor = PreferenceUploadProcessor(api)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }

        assertTrue(processor.process(task(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, """{"key":"library_layout:style","value":"covers"}""")))
        assertTrue(processor.process(task(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, """{"key":"library_display:progress_style","value":"true"}""")))

        assertEquals(
            """{"entries":[{"key":"library_layout:style","value":{"layout":"covers"}}]}""",
            server.takeRequest().body.readUtf8(),
        )
        assertEquals(
            """{"entries":[{"key":"library_display:progress_style","value":{"value":true}}]}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    // One request without a prefix fetches every family, as iOS does; each entry goes through its own family
    @Test fun `fetch asks for every family at once and routes each entry`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"entries":[
                    {"key":"library_layout:style","value":{"layout":"grid"}},
                    {"key":"library_display:progress_style","value":{"value":1}},
                    {"key":"library_display:title_source","value":{"value":false}},
                    {"key":"library_display:unknown","value":{"value":true}},
                    {"key":"something_else","value":{"value":true}}
                ]}"""
            )
        )
        val store = DataStorePreferencesStore(context)

        assertTrue(
            PreferenceFetchProcessor(context, FakeSyncTaskRepository(emptySet()), api)
                .process(task(SyncTaskFactory.JOB_FETCH_PREFERENCES, "{}"))
        )

        assertEquals("/v1/user/preferences", server.takeRequest().path)
        assertEquals("grid", store.getString("library_layout:style"))
        assertEquals("true", store.getString("library_display:progress_style"))
        assertEquals("false", store.getString("library_display:title_source"))
        assertNull(store.getString("library_display:unknown"))
        assertNull(store.getString("something_else"))
    }

    // The watch has no layout or display toggles: it asks for sorts alone and keeps none of the others
    @Test fun `a sort-only fetch asks for the sort prefix and keeps sorts alone`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"entries":[
                    {"key":"library_sort:watch","value":{"sort":"fileName"}},
                    {"key":"library_layout:style","value":{"layout":"covers"}}
                ]}"""
            )
        )
        val store = DataStorePreferencesStore(context)
        store.remove("library_layout:style")

        assertTrue(
            PreferenceFetchProcessor(context, FakeSyncTaskRepository(emptySet()), api, families = listOf(PreferenceFamily.Sort))
                .process(task(SyncTaskFactory.JOB_FETCH_PREFERENCES, "{}"))
        )

        assertEquals("/v1/user/preferences?prefix=library_sort%3A", server.takeRequest().path)
        assertEquals("fileName", store.getString("library_sort:watch"))
        assertNull(store.getString("library_layout:style"))
    }

    /** A coded API answer reaches the engine as a CodedFailureException; an uncoded one stays a retry */
    @Test fun `upload hands a coded API failure to the engine, keeps an uncoded one a retry`() = runBlocking {
        val processor = PreferenceUploadProcessor(api)
        val upload = task(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, """{"key":"library_sort:default","value":"metadataTitle"}""")

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"You are not subscribed","error":"not_subscribed"}"""))
        val coded = runCatching { processor.process(upload) }.exceptionOrNull() as? CodedFailureException
        assertEquals(CodedFailure("not_subscribed", "You are not subscribed", 400), coded?.failure)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"You are not subscribed"}"""))
        assertFalse(processor.process(upload))
        repeat(2) { server.takeRequest() }
    }

    // --- Conflicts go to the newer change (iOS last-write-wins), and changes the server never took go up ---

    private fun roomRepository() = com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository(
        androidx.room.Room.inMemoryDatabaseBuilder(context, com.tortugapower.audiobookplayer.database.AppDatabase::class.java)
            .allowMainThreadQueries().build().syncTaskDao()
    )

    private suspend fun uploads(repository: SyncTaskRepository): Map<String, Map<*, *>> =
        repository.getPendingTasks().filter { it.jobType == SyncTaskFactory.JOB_UPLOAD_PREFERENCE }
            .associate { it.taskID to com.google.gson.Gson().fromJson(it.payload, Map::class.java) }

    private fun at(millis: Long) = java.time.Instant.ofEpochMilli(millis).toString()

    @Test fun `a pulled value newer than the local change replaces it and drops its upload`() = runBlocking {
        val repository = roomRepository()
        val store = com.tortugapower.audiobookplayer.logic.preferences.FakePreferencesStore(mapOf("library_layout:style" to "covers"))
        val changes = PreferenceChanges(store) { 1_000_000L }
        SyncTaskFactory.createUploadPreferenceTask(repository, "library_layout:style", "covers", changes.mark("library_layout:style"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"entries":[{"key":"library_layout:style","value":{"layout":"grid"},"updated_at":"${at(1_000_500L)}"}]}"""
        ))

        assertTrue(PreferenceFetchProcessor(context, repository, api, store = store, changes = changes)
            .process(task(SyncTaskFactory.JOB_FETCH_PREFERENCES, "{}")))
        server.takeRequest()

        assertEquals("grid", store.getString("library_layout:style"))
        assertNull(changes.changedAt("library_layout:style"))
        assertTrue(uploads(repository).isEmpty())
    }

    @Test fun `a local change newer than the pulled value, or a pull without a time, keeps it`() = runBlocking {
        val repository = roomRepository()
        val store = com.tortugapower.audiobookplayer.logic.preferences.FakePreferencesStore(
            mapOf("library_layout:style" to "covers", "library_sort:default" to "fileName")
        )
        val changes = PreferenceChanges(store) { 1_000_000L }
        SyncTaskFactory.createUploadPreferenceTask(repository, "library_layout:style", "covers", changes.mark("library_layout:style"))
        SyncTaskFactory.createUploadPreferenceTask(repository, "library_sort:default", "fileName", changes.mark("library_sort:default"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"entries":[
                {"key":"library_layout:style","value":{"layout":"grid"},"updated_at":"${at(999_000L)}"},
                {"key":"library_sort:default","value":{"sort":"mostRecent"}}
            ]}"""
        ))

        assertTrue(PreferenceFetchProcessor(context, repository, api, store = store, changes = changes)
            .process(task(SyncTaskFactory.JOB_FETCH_PREFERENCES, "{}")))
        server.takeRequest()

        assertEquals("covers", store.getString("library_layout:style"))
        assertEquals("fileName", store.getString("library_sort:default"))
        assertEquals(setOf("library_layout:style", "library_sort:default"), uploads(repository).keys)
    }

    @Test fun `a change the server never took (made while FREE) is uploaded after the pull`() = runBlocking {
        val repository = roomRepository()
        val store = com.tortugapower.audiobookplayer.logic.preferences.FakePreferencesStore(
            mapOf("library_display:progress_style" to "true", "library_layout:style" to "covers")
        )
        val changes = PreferenceChanges(store) { 1_000_000L }
        val changedAt = changes.mark("library_display:progress_style")
        changes.mark("library_layout:style")
        changes.mark("library_sort:gone") // reset since: nothing to send
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"entries":[{"key":"library_layout:style","value":{"layout":"list"},"updated_at":"${at(2_000_000L)}"}]}"""
        ))

        assertTrue(PreferenceFetchProcessor(context, repository, api, store = store, changes = changes)
            .process(task(SyncTaskFactory.JOB_FETCH_PREFERENCES, "{}")))
        server.takeRequest()

        val queued = uploads(repository)
        assertEquals("only the change the server's newer value didn't replace", setOf("library_display:progress_style"), queued.keys)
        assertEquals("true", queued.getValue("library_display:progress_style")["value"])
        assertEquals(changedAt, (queued.getValue("library_display:progress_style")["changedAt"] as Number).toLong())
        assertEquals("list", store.getString("library_layout:style"))
        assertEquals(setOf("library_display:progress_style"), changes.all().keys)
    }

    @Test fun `a taken upload clears the change it carried, not a later one`() = runBlocking {
        val store = com.tortugapower.audiobookplayer.logic.preferences.FakePreferencesStore()
        var now = 1_000L
        val changes = PreferenceChanges(store) { now }
        val sent = changes.mark("library_layout:style")
        val processor = PreferenceUploadProcessor(api, changes)
        val upload = """{"key":"library_layout:style","value":"covers","changedAt":$sent}"""
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }

        now = 2_000L
        changes.mark("library_layout:style") // changed again while the first went up
        assertTrue(processor.process(task(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, upload)))
        assertEquals(2_000L, changes.changedAt("library_layout:style"))

        assertTrue(processor.process(task(SyncTaskFactory.JOB_UPLOAD_PREFERENCE, upload.replace("$sent", "2000"))))
        assertNull(changes.changedAt("library_layout:style"))
        repeat(2) { server.takeRequest() }
    }

    @Test fun `a change made while the pull runs keeps its upload`() = runBlocking {
        val repository = roomRepository()
        val store = com.tortugapower.audiobookplayer.logic.preferences.FakePreferencesStore(mapOf("library_layout:style" to "covers"))
        var now = 1_000_000L
        val changes = PreferenceChanges(store) { now }
        SyncTaskFactory.createUploadPreferenceTask(repository, "library_layout:style", "covers", changes.mark("library_layout:style"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"entries":[{"key":"library_layout:style","value":{"layout":"grid"},"updated_at":"${at(1_000_500L)}"}]}"""
        ))
        // The user picks again between the pull reading the change and clearing it
        val racing = object : PreferenceChanges(store, { now }) {
            override suspend fun clear(key: String, at: Long?) {
                now = 1_001_000L
                SyncTaskFactory.createUploadPreferenceTask(repository, key, "list", changes.mark(key))
                super.clear(key, at)
            }
        }

        assertTrue(PreferenceFetchProcessor(context, repository, api, store = store, changes = racing)
            .process(task(SyncTaskFactory.JOB_FETCH_PREFERENCES, "{}")))
        server.takeRequest()

        assertEquals(1_001_000L, changes.changedAt("library_layout:style"))
        assertEquals("list", uploads(repository).getValue("library_layout:style")["value"])
    }
}
