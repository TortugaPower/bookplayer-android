package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.logic.CodedFailure
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class ApiErrorTest {

    private fun errorResponse(status: Int, body: String): Response<Unit> =
        Response.error(status, body.toResponseBody("application/json".toMediaType()))

    @Test fun aCodedError_yieldsItsCodeMessageAndStatus() {
        val error = ApiError.parse(errorResponse(404, """{"message":"Item not found: \"Book.m4b\"","error":"item_not_found"}"""))

        assertEquals("item_not_found", error.code)
        assertEquals(CodedFailure("item_not_found", "Item not found: \"Book.m4b\"", 404), error.codedFailure())
    }

    /** A 4xx without a code may succeed on a retry: nothing to park on */
    @Test fun anUncodedError_hasNoCodedFailure() {
        val error = ApiError.parse(errorResponse(400, """{"message":"You are not subscribed"}"""))

        assertEquals("You are not subscribed", error.message)
        assertNull(error.code)
        assertNull(error.codedFailure())
    }

    /** S3, a proxy or a load balancer can answer with something that isn't the API's JSON */
    @Test fun aBodyThatIsntTheEnvelope_isKeptRawWithNoCode() {
        val html = "<html><body>502 Bad Gateway</body></html>"
        val error = ApiError.parse(errorResponse(502, html))

        assertNull(error.code)
        assertNull(error.message)
        assertEquals(html, error.rawBody)
        assertEquals(502, error.httpStatus)
    }

    @Test fun aBlankOrNonTextCode_isNoCode() {
        assertNull(ApiError.parse("""{"message":"m","error":""}""", 400).code)
        assertNull(ApiError.parse("""{"message":"m","error":{"nested":true}}""", 400).code)
        assertNull(ApiError.parse(null, 400).code)
    }

    /** The message is optional; the code alone is enough to park */
    @Test fun aCodeWithoutAMessage_stillParks() {
        assertEquals(CodedFailure("uuid_conflict", "", 409), ApiError.parse("""{"error":"uuid_conflict"}""", 409).codedFailure())
    }

    @Test fun throwIfCoded_isNullOnSuccess_throwsACodedFailure_andReturnsAnUncodedOne() {
        assertNull(Response.success(Unit).throwIfCoded())

        val thrown = runCatching { errorResponse(409, """{"message":"m","error":"uuid_conflict"}""").throwIfCoded() }
            .exceptionOrNull() as? com.tortugapower.audiobookplayer.logic.CodedFailureException
        assertEquals(CodedFailure("uuid_conflict", "m", 409), thrown?.failure)

        val uncoded = errorResponse(500, """{"message":"Internal error"}""").throwIfCoded()
        assertEquals("Internal error", uncoded?.message)
        assertEquals("""{"message":"Internal error"}""", uncoded?.rawBody)
    }
}
