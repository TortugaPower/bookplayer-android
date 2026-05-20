package com.tortugapower.audiobookplayer.network

import com.tortugapower.audiobookplayer.BuildConfig

object NetworkConstants {
    val BASE_URL: String = BuildConfig.BASE_URL
    val GOOGLE_CLIENT_ID: String = BuildConfig.GOOGLE_CLIENT_ID

    // Auth Endpoints
    const val ENDPOINT_SEND_VERIFICATION_CODE = "/v1/passkey/verify-email/send"
    const val ENDPOINT_CHECK_VERIFICATION_CODE = "/v1/passkey/verify-email/check"
    const val ENDPOINT_REGISTRATION_OPTIONS = "/v1/passkey/register/options"
    const val ENDPOINT_REGISTRATION_VERIFY = "/v1/passkey/register/verify"
    const val ENDPOINT_PASSKEY_SIGNIN_OPTIONS = "/v1/passkey/auth/options"
    const val ENDPOINT_PASSKEY_VERIFY = "/v1/passkey/auth/verify"
    const val ENDPOINT_GOOGLE_LOGIN = "/v1/user/login"
}
