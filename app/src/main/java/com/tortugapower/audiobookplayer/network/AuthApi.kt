package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.model.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface AuthApi {
    @POST(NetworkConstants.ENDPOINT_SEND_VERIFICATION_CODE)
    suspend fun sendVerificationCode(@Body request: EmailVerificationSendRequest): Response<EmailVerificationSendResponse>

    @POST(NetworkConstants.ENDPOINT_CHECK_VERIFICATION_CODE)
    suspend fun checkVerificationCode(@Body request: EmailVerificationCheckRequest): Response<EmailVerificationCheckResponse>

    @POST(NetworkConstants.ENDPOINT_REGISTRATION_OPTIONS)
    suspend fun getRegistrationOptions(@Body request: PasskeyRegistrationOptionsRequest): Response<PasskeyRegistrationOptions>

    @POST(NetworkConstants.ENDPOINT_PASSKEY_SIGNIN_OPTIONS)
    suspend fun getSignInOptions(@Body request: PasskeySignInOptionsRequest): Response<PasskeySignInOptionsResponse>

    @POST(NetworkConstants.ENDPOINT_REGISTRATION_VERIFY)
    suspend fun verifyRegistration(@Body request: PasskeyRegistrationVerifyRequest): Response<PasskeyLoginResponse>

    @POST(NetworkConstants.ENDPOINT_PASSKEY_VERIFY)
    suspend fun verifyPasskey(@Body request: PasskeyVerifyRequest): Response<PasskeyLoginResponse>

    @POST(NetworkConstants.ENDPOINT_GOOGLE_LOGIN)
    suspend fun googleLogin(@Body request: GoogleLoginRequest): Response<GoogleLoginResponse>

    // Authenticated (Bearer token attached by NetworkClient).
    @DELETE(NetworkConstants.ENDPOINT_DELETE_ACCOUNT)
    suspend fun deleteAccount(): Response<DeleteAccountResponse>

    @GET(NetworkConstants.ENDPOINT_PASSKEY_CREDENTIALS)
    suspend fun listPasskeys(): Response<PasskeyListResponse>

    @DELETE(NetworkConstants.ENDPOINT_PASSKEY_DELETE)
    suspend fun deletePasskey(@Path("id") id: Int): Response<Unit>
}

object NetworkClient {
    private var apiToken: String? = null

    fun setToken(token: String?) {
        apiToken = token
    }

    private val client = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .addHeader("x-platform", "android")
            
            apiToken?.let {
                builder.addHeader("Authorization", "Bearer $it")
            }
            
            chain.proceed(builder.build())
        }
        .build()

    private val retrofit = retrofit2.Retrofit.Builder()
        .baseUrl(NetworkConstants.BASE_URL)
        .client(client)
        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
        .build()

    val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }
    val libraryApi: LibraryApi by lazy { retrofit.create(LibraryApi::class.java) }
}
