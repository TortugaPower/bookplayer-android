package com.tortugapower.audiobookplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.repository.RoomSyncTaskRepository
import com.tortugapower.audiobookplayer.viewmodel.AuthStep
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModel
import com.tortugapower.audiobookplayer.viewmodel.AuthViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthSheet(
    onDismiss: () -> Unit,
    onAuthenticated: (hasSubscription: Boolean) -> Unit
) {
    val context = LocalContext.current

    val db = AppDatabase.getDatabase(context)
    val accountRepository = RoomAccountRepository(db.accountDao())
    val syncTaskRepository = RoomSyncTaskRepository(db.syncTaskDao())
    val viewModel: AuthViewModel = viewModel(
        key = "AuthSheet",
        factory = AuthViewModelFactory(accountRepository, syncTaskRepository)
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Reset when shown
    LaunchedEffect(Unit) {
        viewModel.reset()
    }

    // Passkey registration is triggered by the ViewModel once the email code is verified.
    // The credential UI needs a Context, so the composable drives it from here.
    LaunchedEffect(viewModel.passkeyRegistrationRequested) {
        if (viewModel.passkeyRegistrationRequested) {
            viewModel.registerPasskey(context)
        }
    }

    // Passkey sign-in with an existing credential.
    LaunchedEffect(viewModel.passkeySignInRequested) {
        if (viewModel.passkeySignInRequested) {
            viewModel.signInWithPasskey(context)
        }
    }

    // Any fully-authenticated path lands on SUCCESS — close the whole auth flow
    // (this sheet AND the Pro sheet underneath), distinct from a user cancel.
    LaunchedEffect(viewModel.currentStep) {
        if (viewModel.currentStep == AuthStep.SUCCESS) {
            onAuthenticated(viewModel.authResultHasSubscription)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header — the sheet's drag handle already provides the gap above it.
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
                        onEmailChange = { viewModel.email = it; viewModel.validationError = null },
                        onContinue = { viewModel.onEmailContinue(context) },
                        onPasskeySignIn = { viewModel.onSignInWithPasskey() },
                        validationError = viewModel.validationError
                    )
                }
                AuthStep.CODE_VERIFICATION -> {
                    CodeVerificationScreen(
                        email = viewModel.email,
                        code = viewModel.verificationCode,
                        onCodeChange = { viewModel.verificationCode = it; viewModel.validationError = null },
                        onVerify = { viewModel.onVerifyCode(context) },
                        onResend = { viewModel.onEmailContinue(context) },
                        validationError = viewModel.validationError
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

        // Errors surface as a native alert dialog.
        AuthErrorDialog(message = viewModel.errorMessage) { viewModel.errorMessage = null }
    }
}

/**
 * Native Material alert dialog for auth errors — the Android equivalent of an iOS `.alert()`.
 * Renders only when [message] is non-null; [onDismiss] should clear the error state.
 */
@Composable
fun AuthErrorDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_error)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_ok))
            }
        }
    )
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        val isCodeStep = step == AuthStep.CODE_VERIFICATION
        IconButton(
            onClick = if (isCodeStep) onBack else onClose,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape)
        ) {
            Icon(
                imageVector = if (isCodeStep) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                contentDescription = stringResource(if (isCodeStep) R.string.common_back else R.string.common_close),
                tint = MaterialTheme.colorScheme.primary
            )
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
    onPasskeySignIn: () -> Unit,
    validationError: String?
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
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.EmailAddress },
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

        if (validationError != null) {
            Text(
                text = validationError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = TextAlign.Start
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            enabled = email.isNotEmpty()
        ) {
            Text(stringResource(R.string.common_continue), fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onPasskeySignIn) {
            Text(stringResource(R.string.auth_sign_in_with_passkey), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun CodeVerificationScreen(
    email: String,
    code: String,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    validationError: String?
) {
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = code, selection = TextRange(code.length)))
    }

    // Sync external code change back to internal state (e.g. on reset).
    LaunchedEffect(code) {
        if (code != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = code, selection = TextRange(code.length))
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

        // Segmented 6-digit entry backed by a single BasicTextField. The
        // `contentType = SmsOtpCode` semantics let the OS / keyboard surface the code for
        // one-tap autofill; the decorationBox renders the per-digit boxes.
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
                .focusRequester(focusRequester)
                .semantics { contentType = ContentType.SmsOtpCode },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.Center) {
                    // The next slot to be filled is "active" while the field has focus.
                    val activeIndex = textFieldValue.text.length.coerceAtMost(5)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(6) { index ->
                            val char = textFieldValue.text.getOrNull(index)?.toString() ?: ""
                            val isActive = index == activeIndex
                            Surface(
                                // Flexible width so all 6 boxes fit any screen (fixed widths
                                // overflowed and clipped the last digit on narrow phones).
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                border = if (isActive) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
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
                    // Invisible input field overlay to capture focus and keyboard events.
                    Box(modifier = Modifier.fillMaxWidth().height(56.dp).alpha(0f)) {
                        innerTextField()
                    }
                }
            }
        )

        if (validationError != null) {
            Text(
                text = validationError,
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
        TextButton(onClick = onResend) {
            Text(stringResource(R.string.auth_resend_code), color = MaterialTheme.colorScheme.primary)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
