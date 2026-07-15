package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.entities.SyncTaskEntity
import com.tortugapower.audiobookplayer.database.entities.SyncTaskStatus
import com.tortugapower.audiobookplayer.logic.preferences.DataStorePreferencesStore
import com.tortugapower.audiobookplayer.repository.SyncTaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.AfterClass
import org.junit.Assert.assertEquals
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
}
