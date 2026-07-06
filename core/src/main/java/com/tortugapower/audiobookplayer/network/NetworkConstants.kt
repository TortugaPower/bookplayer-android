package com.tortugapower.audiobookplayer.network

/**
 * Network configuration + endpoints. `BASE_URL`/`GOOGLE_CLIENT_ID` are injected by the host app via
 * [configure] at startup (before any network call) — they come from the app module's flavored
 * `BuildConfig`, which a shared library module can't read. Each target (phone/Wear) supplies its own.
 */
object NetworkConstants {
    lateinit var BASE_URL: String
        private set
    lateinit var GOOGLE_CLIENT_ID: String
        private set

    /** Must be called once at app startup, before the first use of [NetworkClient] or any endpoint. */
    fun configure(baseUrl: String, googleClientId: String) {
        BASE_URL = baseUrl
        GOOGLE_CLIENT_ID = googleClientId
    }

    // Auth Endpoints
    const val ENDPOINT_SEND_VERIFICATION_CODE = "/v1/passkey/verify-email/send"
    const val ENDPOINT_CHECK_VERIFICATION_CODE = "/v1/passkey/verify-email/check"
    const val ENDPOINT_REGISTRATION_OPTIONS = "/v1/passkey/register/options"
    const val ENDPOINT_REGISTRATION_VERIFY = "/v1/passkey/register/verify"
    const val ENDPOINT_PASSKEY_SIGNIN_OPTIONS = "/v1/passkey/auth/options"
    const val ENDPOINT_PASSKEY_VERIFY = "/v1/passkey/auth/verify"
    const val ENDPOINT_GOOGLE_LOGIN = "/v1/user/login"
    const val ENDPOINT_DELETE_ACCOUNT = "/v1/user/delete"
    const val ENDPOINT_PASSKEY_CREDENTIALS = "/v1/passkey/credentials"
    const val ENDPOINT_PASSKEY_DELETE = "/v1/passkey/credentials/{id}"
}
