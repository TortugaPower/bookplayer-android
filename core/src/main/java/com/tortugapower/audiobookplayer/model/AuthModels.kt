package com.tortugapower.audiobookplayer.model

import com.google.gson.annotations.SerializedName

// --- Email Verification ---

data class EmailVerificationSendRequest(
    @SerializedName("email") val email: String
)

data class EmailVerificationSendResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("message") val message: String?
)

data class EmailVerificationCheckRequest(
    @SerializedName("email") val email: String,
    @SerializedName("code") val code: String
)

data class EmailVerificationCheckResponse(
    @SerializedName("verified") val verified: Boolean,
    @SerializedName("verification_token") val verificationToken: String?,
    @SerializedName("message") val message: String?
)

// --- Passkey Registration ---

data class PasskeyRegistrationOptionsRequest(
    @SerializedName("email") val email: String,
    @SerializedName("verification_token") val verificationToken: String?,
    @SerializedName("device_name") val deviceName: String?
)

data class PasskeyCredentialDescriptor(
    @SerializedName("type") val type: String,
    @SerializedName("id") val id: String,
    @SerializedName("transports") val transports: List<String>? = null
)

data class PasskeyRegistrationOptions(
    @SerializedName("challenge") val challenge: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("rp_id") val rpId: String,
    @SerializedName("rp_name") val rpName: String,
    @SerializedName("timeout") val timeout: Int,
    @SerializedName("user_name") val userName: String,
    @SerializedName("user_display_name") val userDisplayName: String,
    @SerializedName("exclude_credentials") val excludeCredentials: List<PasskeyCredentialDescriptor>?
)

data class PasskeyResponse(
    @SerializedName("attestation_object") val attestationObject: String,
    @SerializedName("client_data_json") val clientDataJSON: String,
    @SerializedName("transports") val transports: List<String> = emptyList()
)

data class PasskeyRegistrationVerifyRequest(
    @SerializedName("email") val email: String,
    @SerializedName("credential_id") val credentialId: String,
    @SerializedName("response") val response: PasskeyResponse,
    @SerializedName("device_name") val deviceName: String?
)

data class PasskeyAssertionResponse(
    @SerializedName("client_data_json") val clientDataJSON: String,
    @SerializedName("authenticator_data") val authenticatorData: String,
    @SerializedName("signature") val signature: String,
    @SerializedName("user_handle") val userHandle: String?
)

data class PasskeyVerifyRequest(
    @SerializedName("credential_id") val credentialId: String,
    @SerializedName("response") val response: PasskeyAssertionResponse
)

data class PasskeySignInOptionsRequest(
    @SerializedName("email") val email: String
)

data class PasskeySignInOptionsResponse(
    @SerializedName("challenge") val challenge: String,
    @SerializedName("timeout") val timeout: Int,
    @SerializedName("rp_id") val rpId: String,
    @SerializedName("allow_credentials") val allowCredentials: List<PasskeyCredentialDescriptor>?
)

data class PasskeyLoginResponse(
    @SerializedName("email") val email: String,
    @SerializedName("token") val token: String,
    @SerializedName("external_id") val externalId: String,
    @SerializedName("revenuecat_id") val revenuecatId: String?,
    @SerializedName("has_subscription") val hasSubscription: Boolean
)

// --- Google Login ---

data class GoogleLoginRequest(
    @SerializedName("token_id") val tokenId: String
)

data class GoogleLoginResponse(
    @SerializedName("email") val email: String,
    @SerializedName("token") val token: String,
    @SerializedName("revenuecat_id") val revenuecatId: String?
)

// --- Account ---

data class DeleteAccountResponse(
    @SerializedName("message") val message: String?
)

// --- Passkey management ---

data class PasskeyInfo(
    @SerializedName("id_passkey") val id: Int,
    @SerializedName("device_name") val deviceName: String?,
    @SerializedName("created_at") val createdAt: String?
)

data class PasskeyListResponse(
    // Nullable on purpose: Gson bypasses Kotlin's non-null guarantee and writes a raw null when
    // the server omits the key (or sends `"passkeys": null`). The list call defaults it to empty.
    @SerializedName("passkeys") val passkeys: List<PasskeyInfo>? = null
)
