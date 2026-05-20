package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.tortugapower.audiobookplayer.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.viewmodel.AuthStep
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModel
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModelFactory
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialException
import com.tortugapower.audiobookplayer.model.PasskeyRegistrationVerifyRequest
import com.tortugapower.audiobookplayer.model.PasskeyLoginResponse
import kotlinx.coroutines.launch
import org.json.JSONObject

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
        key = "AuthSheet",
        factory = AuthViewModelFactory(accountRepository)
    )

    val credentialManager = CredentialManager.create(context)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Reset when shown
    LaunchedEffect(Unit) {
        viewModel.reset()
    }

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
                            android.util.Log.d("AuthSheet", "Response JSON: $responseJson")
                            val json = JSONObject(responseJson)
                            val responseObj = json.getJSONObject("response")
                            
                            val loginResponse = com.tortugapower.audiobookplayer.network.NetworkClient.authApi.verifyRegistration(
                                com.tortugapower.audiobookplayer.model.PasskeyRegistrationVerifyRequest(
                                    email = viewModel.email,
                                    credentialId = json.getString("id"),
                                    response = com.tortugapower.audiobookplayer.model.PasskeyResponse(
                                        attestationObject = responseObj.getString("attestationObject"),
                                        clientDataJSON = responseObj.getString("clientDataJSON"),
                                        transports = if (responseObj.has("transports")) {
                                            val arr = responseObj.getJSONArray("transports")
                                            List(arr.length()) { arr.getString(it) }
                                        } else emptyList()
                                    ),
                                    deviceName = deviceName
                                )
                            )
                            
                            if (loginResponse.isSuccessful && loginResponse.body() != null) {
                                viewModel.completeRegistration(loginResponse.body()!!)
                                onDismiss()
                            } else {
                                val errorBody = loginResponse.errorBody()?.string()
                                android.util.Log.e("AuthSheet", "Verify failed: $errorBody")
                                viewModel.errorMessage = context.getString(R.string.auth_error_verification_failed)
                                viewModel.currentStep = AuthStep.CODE_VERIFICATION
                            }
                        }
                    } catch (e: CreateCredentialException) {
                        viewModel.errorMessage = context.getString(R.string.auth_error_passkey_failed, e.message ?: "")
                        viewModel.currentStep = AuthStep.CODE_VERIFICATION
                    }
                }
            } else {
                // Direct success
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
        windowInsets = WindowInsets.statusBars
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.auth_setting_up_secure_access))
                    }
                }
                AuthStep.ERROR -> { }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.primary)
            }
        }

        Text(
            text = when (step) {
                AuthStep.EMAIL_INPUT -> stringResource(R.string.auth_create_account)
                AuthStep.CODE_VERIFICATION -> stringResource(R.string.auth_verify_your_email)
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
            text = stringResource(R.string.auth_email_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = { Text(stringResource(R.string.auth_email_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            trailingIcon = {
                if (email.isNotEmpty()) {
                    IconButton(onClick = { onEmailChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(16.dp))
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
            Text(stringResource(R.string.common_continue), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = { /* Handle existing passkey */ }) {
            Text(stringResource(R.string.auth_sign_in_with_passkey), color = Color(0xFF3482F6))
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
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember { 
        mutableStateOf(
            TextFieldValue(
                text = code,
                selection = TextRange(code.length)
            )
        ) 
    }

    // Sync external code change back to internal state
    LaunchedEffect(code) {
        if (code != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(
                text = code,
                selection = TextRange(code.length)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.auth_enter_code_sent_to, email),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Standard 6-digit code input using BasicTextField for native interaction support
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                if (newValue.text.length <= 6 && newValue.text.all { it.isDigit() }) {
                    textFieldValue = newValue
                    onCodeChange(newValue.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.Center) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        repeat(6) { index ->
                            val char = textFieldValue.text.getOrNull(index)?.toString() ?: ""
                            val isFocused = textFieldValue.selection.collapsed && 
                                           (textFieldValue.selection.start == index || (index == 5 && textFieldValue.selection.start == 6))
                            
                            Surface(
                                modifier = Modifier.size(48.dp, 56.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                border = if (isFocused) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = char,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                    // Invisible input field overlay to capture focus and keyboard events
                    Box(modifier = Modifier.fillMaxWidth().height(56.dp).alpha(0f)) {
                        innerTextField()
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
            Text(stringResource(R.string.common_verify), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.auth_didnt_receive_code),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { /* Resend logic */ }) {
            Text(stringResource(R.string.auth_resend_code), color = Color(0xFF3482F6))
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

