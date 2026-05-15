package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.viewmodel.AuthStep
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModel
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModelFactory

import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.tortugapower.audiobookplayer.network.NetworkConstants
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialException
import com.tortugapower.audiobookplayer.model.PasskeyRegistrationVerifyRequest
import com.tortugapower.audiobookplayer.model.PasskeyLoginResponse
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = AppDatabase.getDatabase(context)
    val accountRepository = RoomAccountRepository(db.accountDao())
    val viewModel: AuthViewModel = viewModel(
        factory = AuthViewModelFactory(accountRepository)
    )

    val credentialManager = CredentialManager.create(context)

    fun handleGoogleSignIn() {
        val googleIdTokenOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(NetworkConstants.GOOGLE_CLIENT_ID)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdTokenOption)
            .build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                
                if (credential is GoogleIdTokenCredential) {
                    viewModel.googleLogin(credential.idToken, credential.id)
                }
            } catch (e: Exception) {
                viewModel.errorMessage = "Google Sign-In failed: ${e.message}"
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Handle Auth success/transitions
    LaunchedEffect(viewModel.currentStep) {
        if (viewModel.currentStep == AuthStep.SUCCESS) {
            if (viewModel.verificationToken != null) {
                // Handle Passkey registration
                val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                val options = viewModel.getPasskeyRegistrationOptions(deviceName)
                
                if (options != null) {
                    try {
                        val requestJson = viewModel.getRegistrationJson(options)
                        val request = CreatePublicKeyCredentialRequest(requestJson)
                        val result = credentialManager.createCredential(context, request)
                        
                        val responseJson = result.data.getString("androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON")
                        if (responseJson != null) {
                            val loginResponse = com.tortugapower.audiobookplayer.network.NetworkClient.authApi.verifyRegistration(
                                PasskeyRegistrationVerifyRequest(
                                    email = viewModel.email,
                                    credentialId = "", 
                                    attestationObject = "", 
                                    clientDataJSON = "", 
                                    transports = null,
                                    deviceName = deviceName
                                )
                            )
                            
                            if (loginResponse.isSuccessful && loginResponse.body() != null) {
                                viewModel.completeRegistration(loginResponse.body()!!)
                                onDismiss()
                            } else {
                                viewModel.errorMessage = "Verification failed"
                                viewModel.currentStep = AuthStep.CODE_VERIFICATION
                            }
                        }
                    } catch (e: CreateCredentialException) {
                        viewModel.errorMessage = "Passkey failed: ${e.message}"
                        viewModel.currentStep = AuthStep.CODE_VERIFICATION
                    }
                }
            } else {
                // Google or other direct success
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = null,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header
            AuthHeader(
                step = viewModel.currentStep,
                onBack = { 
                    if (viewModel.currentStep == AuthStep.CODE_VERIFICATION) {
                        viewModel.currentStep = AuthStep.EMAIL_INPUT
                    } else {
                        onDismiss()
                    }
                },
                onClose = onDismiss
            )

            when (viewModel.currentStep) {
                AuthStep.EMAIL_INPUT -> {
                    EmailInputScreen(
                        email = viewModel.email,
                        onEmailChange = { viewModel.email = it },
                        onContinue = { viewModel.onEmailContinue() },
                        onGoogleSignIn = { handleGoogleSignIn() },
                        errorMessage = viewModel.errorMessage
                    )
                }
                AuthStep.CODE_VERIFICATION -> {
                    CodeVerificationScreen(
                        email = viewModel.email,
                        code = viewModel.verificationCode,
                        onCodeChange = { viewModel.verificationCode = it },
                        onVerify = { viewModel.onVerifyCode() },
                        errorMessage = viewModel.errorMessage
                    )
                }
                AuthStep.LOADING -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                AuthStep.SUCCESS -> {
                    // This state is briefly visible while the Passkey UI is being prepared or active
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Setting up your passkey...")
                    }
                }
                AuthStep.ERROR -> {
                    // Error state handled within the screens via errorMessage
                }
            }
        }
    }
}

@Composable
fun AuthHeader(
    step: AuthStep,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        if (step == AuthStep.CODE_VERIFICATION) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Text(
            text = when (step) {
                AuthStep.EMAIL_INPUT -> "Create Account"
                AuthStep.CODE_VERIFICATION -> "Verify Your Email"
                else -> ""
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun EmailInputScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    onContinue: () -> Unit,
    onGoogleSignIn: () -> Unit,
    errorMessage: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "Email",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            trailingIcon = {
                if (email.isNotEmpty()) {
                    IconButton(onClick = { onEmailChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            }
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (email.isNotEmpty()) Color(0xFF666666) else Color(0xFFCCCCCC)
            ),
            shape = RoundedCornerShape(28.dp),
            enabled = email.isNotEmpty()
        ) {
            Text("Continue", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onGoogleSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Black,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Sign in with Google",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = { /* Handle existing passkey */ }) {
            Text("Sign in with existing passkey", color = Color(0xFF3482F6))
        }
    }
}

@Composable
fun CodeVerificationScreen(
    email: String,
    code: String,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    errorMessage: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter the 6-digit code sent to $email",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(48.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6) onCodeChange(it) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            trailingIcon = {
                if (code.isNotEmpty()) {
                    IconButton(onClick = { onCodeChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            }
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onVerify,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (code.length == 6) Color(0xFF666666) else Color(0xFFCCCCCC)
            ),
            shape = RoundedCornerShape(28.dp),
            enabled = code.length == 6
        ) {
            Text("Verify", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Didn't receive the code?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { /* Resend logic */ }) {
            Text("Resend Code", color = Color(0xFF3482F6))
        }
    }
}
