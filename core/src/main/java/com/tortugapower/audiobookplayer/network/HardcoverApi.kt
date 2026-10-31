package com.tortugapower.audiobookplayer.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException

data class GraphQLRequest(
    @SerializedName("query") val query: String,
    @SerializedName("variables") val variables: Map<String, Any>? = null
)

data class HardcoverAuthor(
    @SerializedName("name") val name: String
)

data class HardcoverContribution(
    @SerializedName("author") val author: HardcoverAuthor?
)

data class HardcoverImage(
    @SerializedName("url") val url: String?
)

data class HardcoverBook(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("image") val image: HardcoverImage?,
    @SerializedName("contributions") val contributions: List<HardcoverContribution>?
) {
    fun getAuthorName(): String {
        return contributions?.mapNotNull { it.author?.name }?.joinToString(", ") ?: "Unknown Author"
    }
}

data class GraphQLResponse<T>(
    @SerializedName("data") val data: T?,
    @SerializedName("errors") val errors: List<Map<String, Any>>?
)

interface HardcoverApi {
    @POST("v1/graphql")
    suspend fun postQuery(
        @Header("Authorization") authHeader: String,
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<JsonObject>>
}

object HardcoverService {
    private const val TAG = "HardcoverService"

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.hardcover.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: HardcoverApi by lazy { retrofit.create(HardcoverApi::class.java) }
    private val gson = Gson()

    /** A GraphQL call that got no data: HTTP [status] (0 = none), or the API's `errors`. */
    class HardcoverRequestException(val status: Int, message: String) : Exception(message)

    /**
     * Sends a GraphQL request with the connected account (iOS `HardcoverService.execute`): the OAuth access token
     * if signed in, else the API key, as `Bearer` (Hardcover's API keys no longer include the scheme). A signed-in
     * token the API refuses (401) is refreshed once and the request sent again: it lasts a week, so a refusal
     * means it was revoked or the clock was off. Never loops. Null `data` when not connected.
     */
    suspend fun execute(query: String, variables: Map<String, Any>? = null): JsonObject? {
        val credential = com.tortugapower.audiobookplayer.logic.HardcoverAccount.credential() ?: return null
        val request = GraphQLRequest(query = query, variables = variables)
        var response = api.postQuery("Bearer ${credential.token}", request)
        if (response.code() == 401 && credential.isOAuth) {
            val refreshed = com.tortugapower.audiobookplayer.logic.HardcoverAccount.replaceRejected(credential.token)
            if (refreshed != null) response = api.postQuery("Bearer $refreshed", request)
        }
        if (!response.isSuccessful) {
            throw HardcoverRequestException(response.code(), "HTTP ${response.code()}")
        }
        val body = response.body()
        if (body?.data == null && !body?.errors.isNullOrEmpty()) {
            throw HardcoverRequestException(response.code(), "GraphQL errors: ${body?.errors}")
        }
        return body?.data
    }

    /**
     * Normalizes a book title into a Hardcover search query: strips part/volume/chapter
     * numbering and leading track numbers, collapses whitespace, and appends the author when
     * present. Shared by the browser UI and the auto-match processor so their search
     * normalization can't drift.
     */
    fun buildSearchString(title: String, author: String): String {
        var cleaned = title
        val patterns = listOf(
            Regex("(?i)\\b(book|part|chapter|volume|vol\\.?)\\s+\\d+\\b"),
            Regex("(?i)\\b\\d+\\s*-\\s*"),
            Regex("(?i)^\\d+\\.\\s*")
        )
        for (pattern in patterns) {
            cleaned = pattern.replace(cleaned, "")
        }
        cleaned = Regex("\\s+").replace(cleaned, " ").trim()
        return if (author.isEmpty()) cleaned else "$cleaned, $author"
    }

    suspend fun searchBooks(query: String): List<HardcoverBook> {
        val graphQLQuery = """
            query GetBooks(${'$'}query: String!, ${'$'}per_page: Int!) {
              search(
                query: ${'$'}query
                query_type: "book"
                per_page: ${'$'}per_page
                page: 1
              ) {
                results
              }
            }
        """.trimIndent()

        return try {
            val data = execute(graphQLQuery, mapOf("query" to query, "per_page" to 20))
            parseSearchResults(data?.getAsJsonObject("search")?.get("results"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error searching books", e)
            emptyList()
        }
    }

    private fun parseSearchResults(resultsElement: com.google.gson.JsonElement?): List<HardcoverBook> {
        val listType = object : TypeToken<List<HardcoverBook>>() {}.type
        return when {
            resultsElement == null -> emptyList()
            resultsElement.isJsonArray ->
                gson.fromJson<List<HardcoverBook>>(resultsElement.asJsonArray, listType) ?: emptyList()
            resultsElement.isJsonPrimitive && resultsElement.asJsonPrimitive.isString ->
                gson.fromJson<List<HardcoverBook>>(resultsElement.asString, listType) ?: emptyList()
            resultsElement.isJsonObject && resultsElement.asJsonObject.has("hits") ->
                resultsElement.asJsonObject.getAsJsonArray("hits").mapNotNull { element ->
                    val doc = element.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("document")
                        ?: return@mapNotNull null
                    try {
                        gson.fromJson(doc, HardcoverBook::class.java)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error parsing book document from hits", e)
                        null
                    }
                }
            else -> emptyList()
        }
    }

    suspend fun getPopularBooks(): List<HardcoverBook> {
        val graphQLQuery = """
            query PopularBooks {
              books(order_by: {users_count: desc}, limit: 20) {
                id
                title
                image {
                  url
                }
                contributions {
                  author {
                    name
                  }
                }
              }
            }
        """.trimIndent()

        return try {
            val books = execute(graphQLQuery)?.getAsJsonArray("books")
            val type = object : TypeToken<List<HardcoverBook>>() {}.type
            gson.fromJson<List<HardcoverBook>>(books, type) ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting popular books", e)
            emptyList()
        }
    }

    suspend fun getBook(id: String): HardcoverBook? {
        val bookId = id.toIntOrNull() ?: return null
        val graphQLQuery = """
            query GetBook(${'$'}id: Int!) {
              books_by_pk(id: ${'$'}id) {
                id
                title
                image {
                  url
                }
                contributions {
                  author {
                    name
                  }
                }
              }
            }
        """.trimIndent()

        return try {
            execute(graphQLQuery, mapOf("id" to bookId))?.getAsJsonObject("books_by_pk")
                ?.let { gson.fromJson(it, HardcoverBook::class.java) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting book by id: $id", e)
            null
        }
    }

    suspend fun saveUserBookStatus(bookId: Int, statusId: Int): Int? {
        val graphQLQuery = """
            mutation InsertUserBook(${'$'}book_id: Int!, ${'$'}status_id: Int!) {
              insert_user_book(
                object: {book_id: ${'$'}book_id, status_id: ${'$'}status_id}
              ) {
                id
              }
            }
        """.trimIndent()

        return try {
            execute(graphQLQuery, mapOf("book_id" to bookId, "status_id" to statusId))
                ?.getAsJsonObject("insert_user_book")?.get("id")?.asInt
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error saving user book status", e)
            null
        }
    }

    /** The connected Hardcover user (`me`), or null when not connected or it couldn't be read. */
    suspend fun me(): HardcoverUser? = try {
        parseMe(execute(ME_QUERY))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Failed to read the connected Hardcover account", e)
        null
    }

    /** `me` comes back as a list with the one user; a single object is read the same way. */
    internal fun parseMe(data: JsonObject?): HardcoverUser? {
        val me = data?.get("me") ?: return null
        val user = when {
            me.isJsonArray -> me.asJsonArray.firstOrNull()
            me.isJsonObject -> me
            else -> null
        } ?: return null
        return runCatching { gson.fromJson(user, HardcoverUser::class.java) }.getOrNull()
    }

    /** The read in progress of [bookId] in [userId]'s library, with the editions its length can come from. */
    suspend fun readInProgress(userId: Int, bookId: Int): ReadInProgressData? =
        execute(READ_IN_PROGRESS_QUERY, mapOf("user_id" to userId, "book_id" to bookId))
            ?.let { gson.fromJson(it, ReadInProgressData::class.java) }

    /** Writes [progress] on the read [readId]; the answer's `error`, if Hardcover refused it. */
    suspend fun updateReadProgress(readId: Int, progress: HardcoverReadProgress): String? {
        val data = execute(UPDATE_PROGRESS_MUTATION, mapOf("id" to readId, "object" to progress.objectFields))
        val result = data?.getAsJsonObject("update_user_book_read")
        val error = result?.get("error")
        return if (error == null || error.isJsonNull) null else error.asString
    }

    const val ME_QUERY = "query Me { me { id username } }"

    /** The started read that isn't finished (Hardcover's "Read in Progress"); needs `read:library`. */
    val READ_IN_PROGRESS_QUERY = """
        query ReadInProgress(${'$'}user_id: Int!, ${'$'}book_id: Int!) {
          user_books(where: {user_id: {_eq: ${'$'}user_id}, book_id: {_eq: ${'$'}book_id}}) {
            id
            status_id
            edition { id pages audio_seconds reading_format_id }
            book {
              default_audio_edition { id pages audio_seconds reading_format_id }
              default_ebook_edition { id pages audio_seconds reading_format_id }
              default_physical_edition { id pages audio_seconds reading_format_id }
              audio_editions: editions(
                where: {reading_format_id: {_eq: 2}, audio_seconds: {_gt: 0}},
                order_by: {users_count: desc},
                limit: 1
              ) { id pages audio_seconds reading_format_id }
              paged_editions: editions(
                where: {pages: {_gt: 0}},
                order_by: {users_count: desc},
                limit: 1
              ) { id pages audio_seconds reading_format_id }
            }
            user_book_reads(where: {finished_at: {_is_null: true}}, order_by: {id: desc}, limit: 1) {
              id
              progress_pages
              progress_seconds
              edition { id pages audio_seconds reading_format_id }
            }
          }
        }
    """.trimIndent()

    val UPDATE_PROGRESS_MUTATION = """
        mutation UpdateProgress(${'$'}id: Int!, ${'$'}object: DatesReadInput!) {
          update_user_book_read(id: ${'$'}id, object: ${'$'}object) {
            error
          }
        }
    """.trimIndent()
}

data class HardcoverUser(
    @SerializedName("id") val id: Int,
    @SerializedName("username") val username: String?
)
