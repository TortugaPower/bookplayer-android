package com.tortugapower.audiobookplayer.viewmodel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.SubscriptionManager
import com.tortugapower.audiobookplayer.model.*
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.network.NetworkConstants
import com.tortugapower.audiobookplayer.repository.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class AuthStep {
    EMAIL_INPUT,
    CODE_VERIFICATION,
    LOADING,
    SUCCESS,
    ERROR
}

/**
 * Owns the entire auth flow: email verification, passkey registration / sign-in, and Google
 * sign-in. The entry points take a [Context] (to host the system credential UI and to resolve
 * user-facing strings) — but no Context is ever retained as a field. Composables stay thin:
 * they call these functions and observe state.
 */
class AuthViewModel(
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val gson = Gson()

    var currentStep by mutableStateOf(AuthStep.EMAIL_INPUT)
    var email by mutableStateOf("")
    var verificationCode by mutableStateOf("")
    // Network / operation failures — surfaced as a modal alert dialog.
    var errorMessage by mutableStateOf<String?>(null)
    // Client-side input validation — surfaced inline next to the field.
    var validationError by mutableStateOf<String?>(null)
    var verificationToken by mutableStateOf<String?>(null)
    // Whether the just-authenticated account already has a subscription. Drives whether the UI
    // presents the "Complete Your Account" paywall after sign-in (mirrors iOS hasSubscription).
    var authResultHasSubscription by mutableStateOf(false)
        private set
    var passkeySignInRequested by mutableStateOf(false)
    var passkeyRegistrationRequested by mutableStateOf(false)

    /**
     * The API's error envelope ({ message, error }) for a non-2xx response. Retrofit's `body()` is
     * ALWAYS null on errors — the payload lives in `errorBody()` — so reading `body()?.message`
     * silently dropped the server's guidance (e.g. "an account with this email already exists")
     * and surfaced a bare status code instead.
     */
    private fun <T> parseServerError(response: retrofit2.Response<T>): Pair<String?, String?> {
        val raw = runCatching { response.errorBody()?.string() }.getOrNull() ?: return null to null
        return runCatching {
            val obj = gson.fromJson(raw, com.google.gson.JsonObject::class.java)
            val message = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            val code = obj.get("error")?.takeIf { it.isJsonPrimitive }?.asString
            message to code
        }.getOrDefault(null to null)
    }

    fun onEmailContinue(context: Context) {
        validationError = null
        if (!isValidEmail(email)) {
            // Client-side validation — stays inline, no network call made.
            validationError = context.getString(R.string.auth_error_invalid_email)
            return
        }

        errorMessage = null // Clear existing error on retry
        viewModelScope.launch {
            currentStep = AuthStep.LOADING
            try {
                val response = NetworkClient.authApi.sendVerificationCode(EmailVerificationSendRequest(email))
                if (response.isSuccessful && response.body()?.success == true) {
                    errorMessage = null // Clear error on success
                    // A fresh code is on its way — never present the previous attempt's digits.
                    verificationCode = ""
                    currentStep = AuthStep.CODE_VERIFICATION
                } else {
                    val (serverMessage, errorCode) = parseServerError(response)
                    errorMessage = when (errorCode) {
                        // Localized for the one case users routinely hit (409).
                        "EMAIL_ALREADY_REGISTERED" -> context.getString(R.string.auth_error_email_already_registered)
                        else -> serverMessage
                            ?: response.body()?.message
                            ?: context.getString(R.string.auth_error_send_code_failed, response.code())
                    }
                    currentStep = AuthStep.EMAIL_INPUT
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: context.getString(R.string.auth_error_generic)
                currentStep = AuthStep.EMAIL_INPUT
            }
        }
    }

    fun onVerifyCode(context: Context) {
        validationError = null
        if (verificationCode.length != 6) {
            // Client-side validation — stays inline, no network call made.
            validationError = context.getString(R.string.auth_error_enter_code)
            return
        }

        errorMessage = null // Clear existing error on retry
        viewModelScope.launch {
            currentStep = AuthStep.LOADING
            try {
                val response = NetworkClient.authApi.checkVerificationCode(
                    EmailVerificationCheckRequest(email, verificationCode)
                )
                val body = response.body()
                if (response.isSuccessful && body?.verified == true) {
                    verificationToken = body.verificationToken
                    errorMessage = null // Clear error on success
                    // Verified — hand off to passkey registration. The UI observes this flag
                    // and calls registerPasskey(context); we stay LOADING until it resolves.
                    passkeyRegistrationRequested = true
                } else {
                    // body() is null on non-2xx — the server's reason lives in errorBody().
                    errorMessage = body?.message
                        ?: parseServerError(response).first
                        ?: context.getString(R.string.auth_error_invalid_code)
                    currentStep = AuthStep.CODE_VERIFICATION
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: context.getString(R.string.auth_error_generic)
                currentStep = AuthStep.CODE_VERIFICATION
            }
        }
    }

    fun onSignInWithPasskey() {
        passkeySignInRequested = true
    }

    /**
     * Register a new passkey after the email code has been verified. Drives the system passkey
     * sheet via Credential Manager, then verifies the attestation with the backend. On success,
     * persists the account and transitions to [AuthStep.SUCCESS]; on failure, returns to
     * [AuthStep.CODE_VERIFICATION] with an error.
     */
    suspend fun registerPasskey(context: Context) {
        if (verificationToken == null) {
            errorMessage = context.getString(R.string.auth_error_verification_expired)
            currentStep = AuthStep.EMAIL_INPUT
            passkeyRegistrationRequested = false
            return
        }
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        try {
            val options = fetchRegistrationOptions(deviceName) ?: run {
                errorMessage = context.getString(R.string.auth_error_verification_failed)
                currentStep = AuthStep.CODE_VERIFICATION
                return
            }

            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.createCredential(
                activityFrom(context),
                CreatePublicKeyCredentialRequest(buildRegistrationRequestJson(options))
            )
            val responseJson = (result as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                ?: run {
                    errorMessage = context.getString(R.string.auth_error_verification_failed)
                    currentStep = AuthStep.CODE_VERIFICATION
                    return
                }

            val json = JSONObject(responseJson)
            val responseObj = json.optJSONObject("response")
            val credentialId = json.optString("id")
            val attestationObject = responseObj?.optString("attestationObject")
            val clientDataJSON = responseObj?.optString("clientDataJSON")
            if (responseObj == null || credentialId.isEmpty() ||
                attestationObject.isNullOrEmpty() || clientDataJSON.isNullOrEmpty()
            ) {
                android.util.Log.e("AuthViewModel", "Malformed passkey registration response")
                errorMessage = context.getString(R.string.auth_error_verification_failed)
                currentStep = AuthStep.CODE_VERIFICATION
                return
            }

            val verify = NetworkClient.authApi.verifyRegistration(
                PasskeyRegistrationVerifyRequest(
                    email = email,
                    credentialId = credentialId,
                    response = PasskeyResponse(
                        attestationObject = attestationObject,
                        clientDataJSON = clientDataJSON,
                        transports = responseObj.optJSONArray("transports")?.let { arr ->
                            List(arr.length()) { arr.optString(it) }
                        } ?: emptyList()
                    ),
                    deviceName = deviceName
                )
            )

            if (verify.isSuccessful && verify.body() != null) {
                persistLoginAndFinish(verify.body()!!)
            } else {
                // Log the server's reason (e.g. the WebAuthn origin/apk-key-hash mismatch a
                // debug-signed build hits against prod) — the UI message stays generic, but this
                // makes the failure diagnosable from logcat instead of a bare status code.
                val (serverMessage, _) = parseServerError(verify)
                android.util.Log.e(
                    "AuthViewModel",
                    "Registration verify failed: HTTP ${verify.code()}${serverMessage?.let { " — $it" } ?: ""}"
                )
                errorMessage = context.getString(R.string.auth_error_verification_failed)
                currentStep = AuthStep.CODE_VERIFICATION
            }
        } catch (e: CreateCredentialCancellationException) {
            // User dismissed the passkey sheet — keep them on the verification step, no error.
            currentStep = AuthStep.CODE_VERIFICATION
        } catch (e: CreateCredentialException) {
            errorMessage = context.getString(R.string.auth_error_passkey_failed, e.message ?: "")
            currentStep = AuthStep.CODE_VERIFICATION
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: context.getString(R.string.auth_error_generic)
            currentStep = AuthStep.CODE_VERIFICATION
        } finally {
            passkeyRegistrationRequested = false
        }
    }

    /**
     * Sign in with an existing passkey. Opens the system credential picker, then verifies the
     * assertion with the backend and persists the account on success.
     */
    suspend fun signInWithPasskey(context: Context) {
        try {
            val options = fetchSignInOptions() ?: run {
                errorMessage = context.getString(R.string.auth_error_verification_failed)
                return
            }

            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(
                activityFrom(context),
                GetCredentialRequest(listOf(GetPublicKeyCredentialOption(buildSignInRequestJson(options))))
            )
            val responseJson = (result.credential as? PublicKeyCredential)?.authenticationResponseJson
                ?: run {
                    errorMessage = context.getString(R.string.auth_error_verification_failed)
                    return
                }

            val json = JSONObject(responseJson)
            val responseObj = json.optJSONObject("response")
            val credentialId = json.optString("id")
            val clientDataJSON = responseObj?.optString("clientDataJSON")
            val authenticatorData = responseObj?.optString("authenticatorData")
            val signature = responseObj?.optString("signature")
            if (responseObj == null || credentialId.isEmpty() ||
                clientDataJSON.isNullOrEmpty() || authenticatorData.isNullOrEmpty() || signature.isNullOrEmpty()
            ) {
                android.util.Log.e("AuthViewModel", "Malformed passkey assertion response")
                errorMessage = context.getString(R.string.auth_error_verification_failed)
                return
            }

            val verify = NetworkClient.authApi.verifyPasskey(
                PasskeyVerifyRequest(
                    credentialId = credentialId,
                    response = PasskeyAssertionResponse(
                        clientDataJSON = clientDataJSON,
                        authenticatorData = authenticatorData,
                        signature = signature,
                        userHandle = if (responseObj.has("userHandle")) responseObj.optString("userHandle") else null
                    )
                )
            )

            if (verify.isSuccessful && verify.body() != null) {
                persistLoginAndFinish(verify.body()!!)
            } else {
                android.util.Log.e("AuthViewModel", "Passkey sign-in verify failed: HTTP ${verify.code()}")
                errorMessage = context.getString(R.string.auth_error_verification_failed)
            }
        } catch (e: GetCredentialCancellationException) {
            // User cancelled — not an error.
        } catch (e: GetCredentialException) {
            android.util.Log.e("AuthViewModel", "Passkey sign-in failed", e)
            errorMessage = context.getString(R.string.auth_error_passkey_failed, e.message ?: "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: context.getString(R.string.auth_error_generic)
        } finally {
            passkeySignInRequested = false
        }
    }

    /**
     * Sign in with Google via Credential Manager's branded "Sign in with Google" flow, then
     * exchange the ID token with the backend.
     */
    suspend fun signInWithGoogle(context: Context) {
        errorMessage = null
        currentStep = AuthStep.LOADING
        try {
            val option = GetSignInWithGoogleOption.Builder(NetworkConstants.GOOGLE_CLIENT_ID).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(activityFrom(context), request)

            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                performGoogleLogin(context, googleCred.idToken, googleCred.id)
            } else {
                errorMessage = context.getString(R.string.auth_error_signin_failed, "unexpected credential type")
                currentStep = AuthStep.EMAIL_INPUT
            }
        } catch (e: GetCredentialCancellationException) {
            // User cancelled — not an error.
            currentStep = AuthStep.EMAIL_INPUT
        } catch (e: GetCredentialException) {
            errorMessage = context.getString(R.string.auth_error_signin_failed, e.message ?: "")
            currentStep = AuthStep.EMAIL_INPUT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = context.getString(R.string.auth_error_signin_failed, e.message ?: "")
            currentStep = AuthStep.EMAIL_INPUT
        }
    }

    fun reset() {
        currentStep = AuthStep.EMAIL_INPUT
        email = ""
        verificationCode = ""
        errorMessage = null
        validationError = null
        verificationToken = null
        authResultHasSubscription = false
        passkeySignInRequested = false
        passkeyRegistrationRequested = false
    }

    // --- internals ---

    private suspend fun performGoogleLogin(context: Context, idToken: String, googleUserId: String) {
        currentStep = AuthStep.LOADING
        try {
            val response = NetworkClient.authApi.googleLogin(GoogleLoginRequest(tokenId = idToken))
            val body = response.body()
            if (response.isSuccessful && body != null) {
                createAndPersistAccount(
                    id = googleUserId, // Stable ID from Google
                    email = body.email, // Email confirmed by our server
                    apiToken = body.token, // Auth token from our server
                    revenuecatId = body.revenuecatId,
                    tier = AccountTier.FREE // Will be updated by RevenueCat later
                )
            } else {
                errorMessage = context.getString(R.string.auth_error_server_login_failed, response.code())
                currentStep = AuthStep.EMAIL_INPUT
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: context.getString(R.string.auth_error_generic)
            currentStep = AuthStep.EMAIL_INPUT
        }
    }

    private suspend fun persistLoginAndFinish(response: PasskeyLoginResponse) {
        createAndPersistAccount(
            id = response.externalId,
            email = response.email,
            apiToken = response.token,
            revenuecatId = response.revenuecatId,
            tier = if (response.hasSubscription) AccountTier.PRO else AccountTier.FREE
        )
    }

    /** Persist the authenticated account, hand the RC id to billing, and finish. */
    private suspend fun createAndPersistAccount(
        id: String,
        email: String,
        apiToken: String,
        revenuecatId: String?,
        tier: AccountTier
    ) {
        val account = AccountEntity(
            id = id,
            email = email,
            apiToken = apiToken,
            tier = tier,
            revenuecatId = revenuecatId
        )
        accountRepository.saveAccount(account)
        // Subscription status is sourced from RevenueCat (the source of truth), mirroring iOS —
        // the server login response carries no subscription flag. This drives whether the UI
        // shows the "Complete Your Account" paywall, and updateAccountTier (inside) reconciles
        // the persisted tier.
        authResultHasSubscription = SubscriptionManager.loginAndCheckSubscription(revenuecatId ?: account.id)
        currentStep = AuthStep.SUCCESS
    }

    private suspend fun fetchRegistrationOptions(deviceName: String): PasskeyRegistrationOptions? {
        return try {
            val response = NetworkClient.authApi.getRegistrationOptions(
                PasskeyRegistrationOptionsRequest(email, verificationToken, deviceName)
            )
            response.body()?.takeIf { response.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuthViewModel", "Exception getting registration options", e)
            null
        }
    }

    private suspend fun fetchSignInOptions(): PasskeySignInOptionsResponse? {
        return try {
            val response = NetworkClient.authApi.getSignInOptions(PasskeySignInOptionsRequest(email))
            response.body()?.takeIf { response.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("AuthViewModel", "Exception getting sign-in options", e)
            null
        }
    }

    private fun buildRegistrationRequestJson(options: PasskeyRegistrationOptions): String {
        val map = mapOf(
            "challenge" to options.challenge,
            "rp" to mapOf("name" to options.rpName, "id" to options.rpId),
            "user" to mapOf("id" to options.userId, "name" to options.userName, "displayName" to options.userDisplayName),
            "pubKeyCredParams" to listOf(mapOf("type" to "public-key", "alg" to -7)),
            "timeout" to options.timeout,
            "attestation" to "none",
            "authenticatorSelection" to mapOf("residentKey" to "required", "userVerification" to "required")
        )
        return gson.toJson(map)
    }

    private fun buildSignInRequestJson(options: PasskeySignInOptionsResponse): String {
        val map = mutableMapOf<String, Any>(
            "challenge" to options.challenge,
            "timeout" to options.timeout,
            "rpId" to options.rpId,
            "userVerification" to "required"
        )
        val allowCredentials = options.allowCredentials
        if (!allowCredentials.isNullOrEmpty()) {
            map["allowCredentials"] = allowCredentials.map { cred ->
                mapOf(
                    "type" to cred.type,
                    "id" to cred.id,
                    "transports" to (cred.transports ?: emptyList<String>())
                )
            }
        }
        return gson.toJson(map)
    }

    /** Unwrap an Activity from a (possibly wrapped) Context for Credential Manager UI hosting. */
    private fun activityFrom(context: Context): Context {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return context
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
}
