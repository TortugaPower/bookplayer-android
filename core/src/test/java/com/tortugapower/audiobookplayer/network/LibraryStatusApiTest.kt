package com.tortugapower.audiobookplayer.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The wire shape of `POST /v1/library/status` (bookplayer-api docs/multipart-uploads.md) */
class LibraryStatusApiTest {

    private val server = MockWebServer()
    private lateinit var api: LibraryApi

    @Before fun setUp() {
        server.start()
        api = retrofit2.Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(LibraryApi::class.java)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun sendsTheUuids_asJson_andReadsBothLists() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"unknown":["u-1"],"unsynced":["u-2"]}"""))

        val answer = api.itemsStatus(mapOf("uuids" to listOf("u-1", "u-2", "u-3"))).body()
        val request = server.takeRequest()

        assertEquals("POST", request.method)
        assertEquals("/v1/library/status", request.path)
        assertEquals(true, request.getHeader("Content-Type")?.startsWith("application/json"))
        assertEquals("""{"uuids":["u-1","u-2","u-3"]}""", request.body.readUtf8())
        assertEquals(listOf("u-1"), answer?.unknown)
        assertEquals(listOf("u-2"), answer?.unsynced)
    }

    /** A reply without a list is malformed: it reads as null, never as an empty "nothing to do" */
    @Test fun aMissingList_readsAsNull() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"unknown":[]}"""))

        val answer = api.itemsStatus(mapOf("uuids" to emptyList<String>())).body()

        assertEquals(emptyList<String>(), answer?.unknown)
        assertNull(answer?.unsynced)
    }
}
