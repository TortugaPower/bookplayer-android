package com.tortugapower.audiobookplayer.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

data class GraphQLRequest(
    val query: String,
    val variables: Map<String, Any>? = null
)

data class HardcoverAuthor(
    val name: String
)

data class HardcoverContribution(
    val author: HardcoverAuthor?
)

data class HardcoverImage(
    val url: String?
)

data class HardcoverBook(
    val id: String,
    val title: String,
    val image: HardcoverImage?,
    val contributions: List<HardcoverContribution>?
) {
    fun getAuthorName(): String {
        return contributions?.mapNotNull { it.author?.name }?.joinToString(", ") ?: "Unknown Author"
    }
}

data class GraphQLResponse<T>(
    val data: T?,
    val errors: List<Map<String, Any>>?
)

interface HardcoverApi {
    @POST("v1/graphql")
    suspend fun postQuery(
        @Header("Authorization") authHeader: String,
        @Body request: GraphQLRequest
    ): Response<GraphQLResponse<JsonObject>>
}

object HardcoverService {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.hardcover.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: HardcoverApi by lazy { retrofit.create(HardcoverApi::class.java) }
    private val gson = Gson()

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

    suspend fun searchBooks(token: String, query: String): List<HardcoverBook> {
        if (token.isBlank()) return emptyList()
        val authHeader = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
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

        val request = GraphQLRequest(
            query = graphQLQuery,
            variables = mapOf(
                "query" to query,
                "per_page" to 20
            )
        )

        return try {
            val response = api.postQuery(authHeader, request)
            if (response.isSuccessful) {
                val body = response.body()
                val data = body?.data
                val search = data?.getAsJsonObject("search")
                val resultsElement = search?.get("results")
                
                if (resultsElement == null) {
                    emptyList()
                } else if (resultsElement.isJsonArray) {
                    val type = object : TypeToken<List<HardcoverBook>>() {}.type
                    gson.fromJson<List<HardcoverBook>>(resultsElement.asJsonArray, type) ?: emptyList()
                } else if (resultsElement.isJsonPrimitive && resultsElement.asJsonPrimitive.isString) {
                    val jsonStr = resultsElement.asString
                    val type = object : TypeToken<List<HardcoverBook>>() {}.type
                    gson.fromJson<List<HardcoverBook>>(jsonStr, type) ?: emptyList()
                } else if (resultsElement.isJsonObject) {
                    val obj = resultsElement.asJsonObject
                    if (obj.has("hits")) {
                        val hitsArray = obj.getAsJsonArray("hits")
                        val parsedBooks = mutableListOf<HardcoverBook>()
                        for (element in hitsArray) {
                            if (element.isJsonObject) {
                                val hitObj = element.asJsonObject
                                val doc = hitObj.getAsJsonObject("document")
                                if (doc != null) {
                                    try {
                                        val book = gson.fromJson(doc, HardcoverBook::class.java)
                                        parsedBooks.add(book)
                                    } catch (e: Exception) {
                                        android.util.Log.e("HardcoverService", "Error parsing book document from hits", e)
                                    }
                                }
                            }
                        }
                        parsedBooks
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            } else {
                android.util.Log.e("HardcoverService", "HTTP Error searching books: ${response.code()} - ${response.errorBody()?.string()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("HardcoverService", "Error searching books", e)
            emptyList()
        }
    }

    suspend fun getPopularBooks(token: String): List<HardcoverBook> {
        if (token.isBlank()) return emptyList()
        val authHeader = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
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

        val request = GraphQLRequest(query = graphQLQuery)

        return try {
            val response = api.postQuery(authHeader, request)
            if (response.isSuccessful) {
                val body = response.body()
                val data = body?.data
                val books = data?.getAsJsonArray("books")
                val type = object : TypeToken<List<HardcoverBook>>() {}.type
                gson.fromJson<List<HardcoverBook>>(books, type) ?: emptyList()
            } else {
                android.util.Log.e("HardcoverService", "HTTP Error getting popular books: ${response.code()} - ${response.errorBody()?.string()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("HardcoverService", "Error getting popular books", e)
            emptyList()
        }
    }

    suspend fun getBook(token: String, id: String): HardcoverBook? {
        if (token.isBlank() || id.isBlank()) return null
        val authHeader = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
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

        val request = GraphQLRequest(
            query = graphQLQuery,
            variables = mapOf("id" to id.toInt())
        )

        return try {
            val response = api.postQuery(authHeader, request)
            if (response.isSuccessful) {
                val body = response.body()
                val data = body?.data
                val bookObj = data?.getAsJsonObject("books_by_pk")
                if (bookObj != null) {
                    gson.fromJson(bookObj, HardcoverBook::class.java)
                } else {
                    null
                }
            } else {
                android.util.Log.e("HardcoverService", "HTTP Error getting book: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("HardcoverService", "Error getting book by id: $id", e)
            null
        }
    }

    suspend fun saveUserBookStatus(token: String, bookId: Int, statusId: Int): Int? {
        if (token.isBlank()) return null
        val authHeader = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"
        val graphQLQuery = """
            mutation InsertUserBook(${'$'}book_id: Int!, ${'$'}status_id: Int!) {
              insert_user_book(
                object: {book_id: ${'$'}book_id, status_id: ${'$'}status_id}
              ) {
                id
              }
            }
        """.trimIndent()

        val request = GraphQLRequest(
            query = graphQLQuery,
            variables = mapOf(
                "book_id" to bookId,
                "status_id" to statusId
            )
        )

        return try {
            val response = api.postQuery(authHeader, request)
            if (response.isSuccessful) {
                val body = response.body()
                val data = body?.data
                val insertUserBook = data?.getAsJsonObject("insert_user_book")
                insertUserBook?.get("id")?.asInt
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
