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
        val response = send(
            credential,
            GraphQLRequest(query = query, variables = variables),
            replaceRejected = com.tortugapower.audiobookplayer.logic.HardcoverAccount::replaceRejected,
            post = { auth, request -> api.postQuery(auth, request) },
        )
        if (!response.isSuccessful) {
            throw HardcoverRequestException(response.code(), "HTTP ${response.code()}")
        }
        val body = response.body()
        if (body?.data == null && !body?.errors.isNullOrEmpty()) {
            throw HardcoverRequestException(response.code(), "GraphQL errors: ${body?.errors}")
        }
        return body?.data
    }

    /** Sends [request] with [credential]; a refused OAuth token is replaced once and the request sent again. */
    internal suspend fun <T> send(
        credential: com.tortugapower.audiobookplayer.logic.HardcoverCredential,
        request: GraphQLRequest,
        replaceRejected: suspend (rejected: String) -> String?,
        post: suspend (authHeader: String, request: GraphQLRequest) -> Response<T>,
    ): Response<T> {
        val response = post("Bearer ${credential.token}", request)
        if (response.code() != 401 || !credential.isOAuth) return response
        val refreshed = replaceRejected(credential.token)
        // A failed refresh (offline, rate limited) can hand back the refused token: sent again, it's refused again.
        if (refreshed == null || refreshed == credential.token) return response
        return post("Bearer $refreshed", request)
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

    /**
     * Adds the book to the user's Hardcover library, or sets its status there. [editionId] is the edition a book they
     * don't have yet goes in as ([HardcoverLibraryEntry.editionForNewEntry]); null lets Hardcover pick.
     */
    suspend fun saveUserBookStatus(bookId: Int, statusId: Int, editionId: Int? = null): Int? {
        val graphQLQuery = """
            mutation InsertUserBook(${'$'}object: UserBookCreateInput!) {
              insert_user_book(object: ${'$'}object) {
                id
              }
            }
        """.trimIndent()
        val bookObject = buildMap<String, Any> {
            put("book_id", bookId)
            put("status_id", statusId)
            editionId?.let { put("edition_id", it) }
        }

        // Throws its failures: the status task retries the ones Hardcover may get over (progress waits behind it)
        return execute(graphQLQuery, mapOf("object" to bookObject))
            ?.getAsJsonObject("insert_user_book")?.get("id")?.asInt
    }

    /** Whether the user already has the book, and its audiobook editions with a duration; throws when it can't tell. */
    suspend fun libraryEntry(userId: Int, bookId: Int): HardcoverLibraryEntry? =
        execute(LIBRARY_ENTRY_QUERY, mapOf("user_id" to userId, "book_id" to bookId))
            ?.let { gson.fromJson(it, HardcoverLibraryEntry::class.java) }

    /** The connected Hardcover user (`me`), or null when not connected or it couldn't be read. */
    suspend fun me(): HardcoverUser? = try {
        meOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Failed to read the connected Hardcover account", e)
        null
    }

    /**
     * As [me], but a failure to read it throws: a [HardcoverRequestException] with the HTTP status (a refused key is
     * a 401), or the transport's error. Null only when not connected, or the answer held no user.
     */
    suspend fun meOrThrow(): HardcoverUser? = parseMe(execute(ME_QUERY))

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

    /** Needs `read:library` and `read:catalog` (iOS `libraryEntryQuery`). */
    val LIBRARY_ENTRY_QUERY = """
        query LibraryEntry(${'$'}user_id: Int!, ${'$'}book_id: Int!) {
          user_books(where: {user_id: {_eq: ${'$'}user_id}, book_id: {_eq: ${'$'}book_id}}) {
            id
          }
          books_by_pk(id: ${'$'}book_id) {
            default_audio_edition {
              id
              audio_seconds
            }
            audio_editions: editions(
              where: {reading_format_id: {_eq: 2}, audio_seconds: {_gt: 0}},
              order_by: {users_count: desc},
              limit: 1
            ) {
              id
              audio_seconds
            }
          }
        }
    """.trimIndent()

    /**
     * The started read that isn't finished (Hardcover's "Read in Progress"), with its edition, which progress is
     * measured against; needs `read:library`.
     */
    val READ_IN_PROGRESS_QUERY = """
        query ReadInProgress(${'$'}user_id: Int!, ${'$'}book_id: Int!) {
          user_books(where: {user_id: {_eq: ${'$'}user_id}, book_id: {_eq: ${'$'}book_id}}) {
            id
            status_id
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

/**
 * What adding a book needs to know first (iOS `HardcoverLibraryEntryData`): whether the user already has it, and the
 * audiobook edition to add it with.
 */
data class HardcoverLibraryEntry(
    @SerializedName("user_books") val userBooks: List<UserBook>?,
    @SerializedName("books_by_pk") val book: Book?,
) {
    data class UserBook(@SerializedName("id") val id: Int)

    data class Book(
        @SerializedName("default_audio_edition") val defaultAudioEdition: Edition?,
        /** The most read audiobook edition with a duration, for books whose default has none. */
        @SerializedName("audio_editions") val audioEditions: List<Edition>?,
    ) {
        /** The default audiobook edition when it has a duration, else the most read one with a duration. */
        val audiobookEditionId: Int?
            get() = defaultAudioEdition?.takeIf { (it.audioSeconds ?: 0) > 0 }?.id
                ?: audioEditions?.firstOrNull { (it.audioSeconds ?: 0) > 0 }?.id
    }

    data class Edition(
        @SerializedName("id") val id: Int,
        @SerializedName("audio_seconds") val audioSeconds: Int?,
    )

    /**
     * The edition a book the user doesn't have yet goes in as: its audiobook one. Hardcover would pick the print one,
     * and the read it starts takes the entry's edition, so listening would count as pages. Null when they already have
     * the book (it keeps its edition) or it has no audiobook edition with a duration.
     */
    val editionForNewEntry: Int?
        get() = if (userBooks.isNullOrEmpty()) book?.audiobookEditionId else null
}

data class HardcoverUser(
    @SerializedName("id") val id: Int,
    @SerializedName("username") val username: String?
)
