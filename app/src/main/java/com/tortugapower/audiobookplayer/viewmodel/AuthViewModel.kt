package com.tortugapower.audiobookplayer.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.model.*
import com.tortugapower.audiobookplayer.network.NetworkClient
import com.tortugapower.audiobookplayer.repository.AccountRepository
import kotlinx.coroutines.launch

enum class AuthStep {
    EMAIL_INPUT,
    CODE_VERIFICATION,
    LOADING,
    SUCCESS,
    ERROR
}

class AuthViewModel(
    private val accountRepository: AccountRepository
) : ViewModel() {

    var currentStep by mutableStateOf(AuthStep.EMAIL_INPUT)
    var email by mutableStateOf("")
    var verificationCode by mutableStateOf("")
    var errorMessage by mutableStateOf<String?>(null)
    var verificationToken by mutableStateOf<String?>(null)

    fun onEmailContinue() {
        if (!isValidEmail(email)) {
            errorMessage = "Please enter a valid email"
            return
        }

        viewModelScope.launch {
            currentStep = AuthStep.LOADING
            try {
                val response = NetworkClient.authApi.sendVerificationCode(EmailVerificationSendRequest(email))
                if (response.isSuccessful && response.body()?.success == true) {
                    currentStep = AuthStep.CODE_VERIFICATION
                } else {
                    errorMessage = response.body()?.message ?: "Failed to send code"
                    currentStep = AuthStep.EMAIL_INPUT
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "An error occurred"
                currentStep = AuthStep.EMAIL_INPUT
            }
        }
    }

    fun onVerifyCode() {
        if (verificationCode.length != 6) {
            errorMessage = "Please enter the 6-digit code"
            return
        }

        viewModelScope.launch {
            currentStep = AuthStep.LOADING
            try {
                val response = NetworkClient.authApi.checkVerificationCode(
                    EmailVerificationCheckRequest(email, verificationCode)
                )
                val body = response.body()
                if (response.isSuccessful && body?.verified == true) {
                    verificationToken = body.verificationToken
                    // Next step is handled by the UI to trigger Passkey registration
                    currentStep = AuthStep.SUCCESS 
                } else {
                    errorMessage = body?.message ?: "Invalid code"
                    currentStep = AuthStep.CODE_VERIFICATION
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "An error occurred"
                currentStep = AuthStep.CODE_VERIFICATION
            }
        }
    }

    suspend fun getPasskeyRegistrationOptions(deviceName: String): PasskeyRegistrationOptions? {
        return try {
            val response = NetworkClient.authApi.getRegistrationOptions(
                PasskeyRegistrationOptionsRequest(email, verificationToken, deviceName)
            )
            val body = response.body()
            if (response.isSuccessful && body != null) {
                android.util.Log.d("AuthViewModel", "RP ID: ${body.rpId}")
                android.util.Log.d("AuthViewModel", "RP Name: ${body.rpName}")
                android.util.Log.d("AuthViewModel", "Challenge: ${body.challenge}")
                body
            } else {
                android.util.Log.e("AuthViewModel", "Failed to get options: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("AuthViewModel", "Exception getting options", e)
            null
        }
    }

    fun completeRegistration(loginResponse: PasskeyLoginResponse) {
        viewModelScope.launch {
            val account = AccountEntity(
                id = loginResponse.externalId,
                email = loginResponse.email,
                apiToken = loginResponse.token,
                tier = if (loginResponse.hasSubscription) AccountTier.PRO else AccountTier.FREE
            )
            accountRepository.saveAccount(account)
            currentStep = AuthStep.SUCCESS
        }
    }

    fun googleLogin(googleIdToken: String, googleUserId: String) {
        viewModelScope.launch {
            currentStep = AuthStep.LOADING
            try {
                val response = NetworkClient.authApi.googleLogin(GoogleLoginRequest(googleIdToken))
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val account = AccountEntity(
                        id = googleUserId, // Stable ID from Google
                        email = body.email, // Email confirmed by our server
                        apiToken = body.token, // Auth token from our server
                        tier = AccountTier.FREE // Will be updated by RevenueCat later
                    )
                    accountRepository.saveAccount(account)
                    currentStep = AuthStep.SUCCESS
                } else {
                    errorMessage = "Server login failed: ${response.code()}"
                    currentStep = AuthStep.EMAIL_INPUT
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "An error occurred"
                currentStep = AuthStep.EMAIL_INPUT
            }
        }
    }

    fun reset() {
        currentStep = AuthStep.EMAIL_INPUT
        email = ""
        verificationCode = ""
        errorMessage = null
        verificationToken = null
    }

    fun getRegistrationJson(options: PasskeyRegistrationOptions): String {
        val gson = com.google.gson.Gson()
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

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }
}
