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

data class PasskeyResponse(
    @SerializedName("attestation_object") val attestationObject: String,
    @SerializedName("client_data_json") val clientDataJSON: String,
    val transports: List<String> = emptyList()
)

data class PasskeyRegistrationVerifyRequest(
    val email: String,
    @SerializedName("credential_id") val credentialId: String,
    val response: PasskeyResponse,
    @SerializedName("device_name") val deviceName: String?
)

data class PasskeyAssertionResponse(
    @SerializedName("client_data_json") val clientDataJSON: String,
    @SerializedName("authenticator_data") val authenticatorData: String,
    val signature: String,
    @SerializedName("user_handle") val userHandle: String?
)

data class PasskeyVerifyRequest(
    @SerializedName("credential_id") val credentialId: String,
    val response: PasskeyAssertionResponse
)

data class PasskeySignInOptionsRequest(
    val email: String
)

data class PasskeySignInOptionsResponse(
    val challenge: String,
    val timeout: Int,
    @SerializedName("rp_id") val rpId: String,
    @SerializedName("allow_credentials") val allowCredentials: List<PasskeyCredentialDescriptor>?
)

data class PasskeyLoginResponse(
    val email: String,
    val token: String,
    @SerializedName("external_id") val externalId: String,
    @SerializedName("revenuecat_id") val revenuecatId: String?,
    @SerializedName("has_subscription") val hasSubscription: Boolean
)

// --- Google Login ---

data class GoogleLoginRequest(
    @SerializedName("token_id") val tokenId: String
)

data class GoogleLoginResponse(
    val email: String,
    val token: String
)

