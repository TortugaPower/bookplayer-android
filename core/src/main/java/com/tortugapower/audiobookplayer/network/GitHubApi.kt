package com.tortugapower.audiobookplayer.network

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

data class GitHubContributor(
    // Nullable: Gson injects null into non-null Kotlin fields when the JSON omits a key (e.g.
    // anonymous contributors). Callers filter out incomplete entries before rendering.
    @SerializedName("login") val login: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
)

/** Public GitHub API — used to show the Tip Jar contributors grid. */
interface GitHubApi {
    @GET("repos/TortugaPower/bookplayer-android/contributors")
    suspend fun contributors(): List<GitHubContributor>
}

object GitHubClient {
    val api: GitHubApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubApi::class.java)
    }
}
