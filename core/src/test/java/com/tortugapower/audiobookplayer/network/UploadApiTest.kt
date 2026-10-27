package com.tortugapower.audiobookplayer.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The wire shapes of the multipart routes (bookplayer-api docs/multipart-uploads.md) */
class UploadApiTest {

    private val server = MockWebServer()
    private lateinit var api: UploadApi

    @Before fun setUp() {
        server.start()
        api = retrofit2.Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(UploadApi::class.java)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun start_sendsTheSizes_andReadsBothAnswers() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"started","uploadId":"up-1","partSize":67108864,"partCount":3}"""))
        server.enqueue(MockResponse().setBody("""{"status":"exists"}"""))

        val started = api.start(StartUploadRequest("book-uuid", 150_000_000, 67_108_864)).body()
        val request = server.takeRequest()
        val exists = api.start(StartUploadRequest("book-uuid", 1, 67_108_864)).body()

        assertEquals("/v1/library/upload/start", request.path)
        assertEquals("""{"uuid":"book-uuid","fileSize":150000000,"partSize":67108864}""", request.body.readUtf8())
        assertEquals(StartUploadResponse("started", "up-1", 67_108_864), started)
        assertEquals(StartUploadResponse.EXISTS, exists?.status)
        assertNull(exists?.uploadId)
    }

    @Test fun partUrls_andUploadedParts_andComplete() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"parts":[{"partNumber":2,"url":"https://s3/p2","expiresAt":1}]}"""))
        server.enqueue(MockResponse().setBody("""{"parts":[{"partNumber":1,"size":5242880}]}"""))
        server.enqueue(MockResponse().setBody("""{"synced":true}"""))

        val urls = api.partUrls(PartUrlsRequest("book-uuid", "up/1", listOf(2))).body()
        val urlsRequest = server.takeRequest()
        val uploaded = api.uploadedParts("book-uuid", "up/1+x").body()
        val listRequest = server.takeRequest()
        val complete = api.complete(CompleteUploadRequest("book-uuid", "up/1", 2, 6_000_000))
        val completeRequest = server.takeRequest()

        assertEquals("""{"uuid":"book-uuid","uploadId":"up/1","partNumbers":[2]}""", urlsRequest.body.readUtf8())
        assertEquals(listOf(PartUrl(2, "https://s3/p2")), urls?.parts)
        assertEquals("/v1/library/upload/parts?uuid=book-uuid&uploadId=up%2F1%2Bx", listRequest.path)
        assertEquals(listOf(UploadedPart(1, 5_242_880)), uploaded?.parts)
        assertEquals(200, complete.code())
        assertEquals("""{"uuid":"book-uuid","uploadId":"up/1","partCount":2,"fileSize":6000000}""", completeRequest.body.readUtf8())
    }
}
