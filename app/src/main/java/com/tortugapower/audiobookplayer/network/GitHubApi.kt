package com.tortugapower.audiobookplayer.network

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

data class GitHubContributor(
    val login: String,
    @SerializedName("avatar_url") val avatarUrl: String,
    @SerializedName("html_url") val htmlUrl: String,
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
