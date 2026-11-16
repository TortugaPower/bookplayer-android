package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.logic.HardcoverCredential
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/** `HardcoverService.execute`'s retry: a refused OAuth token is replaced once, and only by a new one. */
class HardcoverSendTest {

    private val request = GraphQLRequest(query = "{ me { id } }")
    private val sent = mutableListOf<String>()
    private val replaced = mutableListOf<String>()

    private fun reply(code: Int): Response<String> =
        if (code == 200) Response.success("ok") else Response.error(code, "".toResponseBody(null))

    private fun send(credential: HardcoverCredential, replacement: String?, codes: List<Int>) = runBlocking {
        val answers = codes.iterator()
        HardcoverService.send(
            credential, request,
            replaceRejected = { replaced += it; replacement },
            post = { auth, _ -> sent += auth; reply(answers.next()) },
        ).code()
    }

    @Test fun `a refused OAuth token is replaced and the request sent once more`() {
        assertEquals(200, send(HardcoverCredential("old", isOAuth = true), "new", listOf(401, 200)))
        assertEquals(listOf("Bearer old", "Bearer new"), sent)
        assertEquals(listOf("old"), replaced)
    }

    @Test fun `a second refusal isn't retried again`() {
        assertEquals(401, send(HardcoverCredential("old", isOAuth = true), "new", listOf(401, 401)))
        assertEquals(2, sent.size)
    }

    @Test fun `the same token back from a failed refresh isn't sent again`() {
        assertEquals(401, send(HardcoverCredential("old", isOAuth = true), "old", listOf(401)))
        assertEquals(listOf("Bearer old"), sent)
    }

    @Test fun `no replacement, an API key, or another error sends once`() {
        assertEquals(401, send(HardcoverCredential("old", isOAuth = true), null, listOf(401)))
        assertEquals(401, send(HardcoverCredential("key", isOAuth = false), "new", listOf(401)))
        assertEquals(500, send(HardcoverCredential("old", isOAuth = true), "new", listOf(500)))
        assertEquals(3, sent.size)
        assertEquals(listOf("old"), replaced)
    }
}
