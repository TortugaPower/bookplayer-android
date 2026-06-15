package com.tortugapower.audiobookplayer.logic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import com.google.gson.Gson
import com.tortugapower.audiobookplayer.model.*
import com.tortugapower.audiobookplayer.network.NetworkClient
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Raised when the user dismisses the system passkey sheet; callers should treat it as a no-op. */
class PasskeyCancelledException : Exception()

/**
 * Account-level passkey management (list / add / delete) for an already-authenticated user.
 *
 * Distinct from the sign-up / sign-in flow in [com.tortugapower.audiobookplayer.viewmodel.AuthViewModel]:
 * adding a passkey here reuses the registration endpoints with **no** verification token — the
 * server binds the challenge to the JWT user (the Bearer token attached by [NetworkClient]).
 * Mirrors iOS `PasskeyService.addPasskeyToAccount` / `listPasskeys` / `deletePasskey`.
 */
object PasskeyManager {

    private val gson = Gson()

    suspend fun listPasskeys(): Result<List<PasskeyInfo>> {
        return try {
            val response = NetworkClient.authApi.listPasskeys()
            if (response.isSuccessful) {
                Result.success(response.body()?.passkeys ?: emptyList())
            } else {
                Result.failure(Exception("Failed to load passkeys (${response.code()})"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePasskey(id: Int): Result<Unit> {
        return try {
            val response = NetworkClient.authApi.deletePasskey(id)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val message = if (response.code() == 403) {
                    "This is your only sign-in method, so it can't be removed."
                } else {
                    "Failed to remove passkey (${response.code()})"
                }
                Result.failure(Exception(message))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addPasskey(context: Context, email: String): Result<Unit> {
        return try {
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            // Authenticated add: no verification token — the server binds the challenge to the JWT user.
            val optionsResponse = NetworkClient.authApi.getRegistrationOptions(
                PasskeyRegistrationOptionsRequest(email, null, deviceName)
            )
            val options = optionsResponse.body()?.takeIf { optionsResponse.isSuccessful }
                ?: return Result.failure(Exception("Couldn't start passkey setup (${optionsResponse.code()})"))

            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.createCredential(
                activityFrom(context),
                CreatePublicKeyCredentialRequest(buildRegistrationRequestJson(options))
            )
            val responseJson = (result as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                ?: return Result.failure(Exception("Unexpected passkey response"))

            val json = JSONObject(responseJson)
            val responseObj = json.optJSONObject("response")
            val credentialId = json.optString("id")
            val attestationObject = responseObj?.optString("attestationObject")
            val clientDataJSON = responseObj?.optString("clientDataJSON")
            if (responseObj == null || credentialId.isEmpty() ||
                attestationObject.isNullOrEmpty() || clientDataJSON.isNullOrEmpty()
            ) {
                return Result.failure(Exception("Malformed passkey response"))
            }

            // Server returns a login response; ignore it — the user is already authenticated.
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
            if (verify.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("Failed to register passkey (${verify.code()})"))
        } catch (e: CreateCredentialCancellationException) {
            Result.failure(PasskeyCancelledException())
        } catch (e: CreateCredentialException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
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

    private fun activityFrom(context: Context): Context {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return context
    }
}
