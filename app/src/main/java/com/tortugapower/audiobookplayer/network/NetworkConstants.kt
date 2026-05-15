package com.tortugapower.audiobookplayer.network

object NetworkConstants {
    const val BASE_URL = "http://192.168.100.157:5003"

    // Auth Endpoints
    const val ENDPOINT_SEND_VERIFICATION_CODE = "/v1/passkey/verify-email/send"
    const val ENDPOINT_CHECK_VERIFICATION_CODE = "/v1/passkey/verify-email/check"
    const val ENDPOINT_REGISTRATION_OPTIONS = "/v1/passkey/register/options"
    const val ENDPOINT_REGISTRATION_VERIFY = "/v1/passkey/register/verify"
    const val ENDPOINT_GOOGLE_LOGIN = "/v1/user/login"

    // Auth Config
    const val GOOGLE_CLIENT_ID = "YOUR_GOOGLE_CLIENT_ID.apps.googleusercontent.com"
}
