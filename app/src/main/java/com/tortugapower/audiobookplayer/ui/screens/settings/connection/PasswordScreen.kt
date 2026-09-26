package com.tortugapower.audiobookplayer.ui.screens.settings.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowUiState
import kotlinx.coroutines.delay

/**
 * Screen 3 · Password. Typing is the only thing this screen does, so the username field always
 * auto-focuses; Return on the password field submits once both fields have content.
 */
@Composable
fun PasswordScreen(
    state: ConnectionFlowUiState,
    onBack: () -> Unit,
    onUsernameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A beat after the push lands, so the keyboard doesn't fight the transition.
        delay(100)
        if (state.username.isEmpty()) usernameFocus.requestFocus() else passwordFocus.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            FlowHeader(
                title = stringResource(R.string.media_servers_add_server_sign_in_button),
                navigation = FlowNavigation.BACK,
                onNavigate = onBack,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowSectionLabel(stringResource(R.string.media_servers_login_section_title))
                    FlowCard {
                        TextField(
                            value = state.username,
                            onValueChange = onUsernameChanged,
                            placeholder = { Text(stringResource(R.string.media_servers_add_server_username_placeholder)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(usernameFocus)
                                .semantics { contentType = ContentType.Username },
                            colors = transparentFieldColors(),
                            singleLine = true,
                            enabled = !state.isLoading,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                            trailingIcon = {
                                if (state.username.isNotEmpty()) {
                                    IconButton(onClick = { onUsernameChanged("") }) {
                                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)
                        TextField(
                            value = state.password,
                            onValueChange = onPasswordChanged,
                            placeholder = { Text(stringResource(R.string.media_servers_add_server_password_placeholder)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(passwordFocus)
                                .semantics { contentType = ContentType.Password },
                            colors = transparentFieldColors(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !state.isLoading,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (state.canSignIn) onSignIn() }),
                        )
                    }
                }

                FlowPrimaryButton(
                    title = stringResource(R.string.media_servers_add_server_sign_in_button),
                    enabled = state.canSignIn,
                    onClick = onSignIn,
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (state.isLoading) FlowLoadingOverlay()
    }
}
