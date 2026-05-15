package com.tortugapower.audiobookplayer.model

import com.google.gson.annotations.SerializedName

// --- Email Verification ---

data class EmailVerificationSendRequest(
    val email: String
)

data class EmailVerificationSendResponse(
    val success: Boolean,
    @SerializedName("expires_in") val expiresIn: Int,
    val message: String?
)

data class EmailVerificationCheckRequest(
    val email: String,
    val code: String
)

data class EmailVerificationCheckResponse(
    val verified: Boolean,
    @SerializedName("verification_token") val verificationToken: String?,
    val message: String?
)

// --- Passkey Registration ---

data class PasskeyRegistrationOptionsRequest(
    val email: String,
    @SerializedName("verification_token") val verificationToken: String?,
    @SerializedName("device_name") val deviceName: String?
)

data class PasskeyCredentialDescriptor(
    val type: String,
    val id: String,
    val transports: List<String>? = null
)

data class PasskeyRegistrationOptions(
    val challenge: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("rp_id") val rpId: String,
    @SerializedName("rp_name") val rpName: String,
    val timeout: Int,
    @SerializedName("user_name") val userName: String,
    @SerializedName("user_display_name") val userDisplayName: String,
    @SerializedName("exclude_credentials") val excludeCredentials: List<PasskeyCredentialDescriptor>?
)

data class PasskeyRegistrationVerifyRequest(
    val email: String,
    @SerializedName("credentialId") val credentialId: String,
    @SerializedName("attestationObject") val attestationObject: String,
    @SerializedName("clientDataJSON") val clientDataJSON: String,
    val transports: List<String>?,
    @SerializedName("deviceName") val deviceName: String?
)

data class PasskeyLoginResponse(
    val email: String,
    val token: String,
    @SerializedName("external_id") val externalId: String,
    @SerializedName("revenuecat_id") val revenuecatId: String,
    @SerializedName("has_subscription") val hasSubscription: Boolean
)

// --- Google Login ---

data class GoogleLoginRequest(
    val token: String
)

data class GoogleLoginResponse(
    val email: String,
    val token: String
)

