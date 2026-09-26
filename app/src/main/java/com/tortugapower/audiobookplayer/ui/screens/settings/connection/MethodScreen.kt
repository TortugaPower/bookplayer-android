package com.tortugapower.audiobookplayer.ui.screens.settings.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.network.AlternativeSignIn
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowUiState

/**
 * Screen 2 · Method. "How do you want to sign in?" — rendered only when the server offers an
 * alternative to the password. The alternative is primary; the password path, when the server
 * accepts one at all, is secondary. No method-explanation copy: the address in the title says which
 * server this is, the Name section is a fact the server told us.
 */
@Composable
fun MethodScreen(
    state: ConnectionFlowUiState,
    onBack: () -> Unit,
    onStartAlternative: () -> Unit,
    onUsePassword: () -> Unit,
    onShowHeaders: () -> Unit,
) {
    val primaryTitle = when (val alternative = state.alternativeSignIn) {
        is AlternativeSignIn.Oidc -> alternative.buttonText ?: stringResource(R.string.media_servers_sso_button)
        AlternativeSignIn.QuickConnect -> stringResource(R.string.media_servers_quick_connect_button)
        // Unreachable by routing: this screen is only pushed when an alternative exists.
        null -> stringResource(R.string.media_servers_add_server_sign_in_button)
    }
    val headerCount = state.headers.count { it.key.isNotBlank() && it.value.isNotBlank() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The address, scheme stripped — it identifies which server this is. A title has no room for "https://".
            FlowHeader(title = state.displayAddress, navigation = FlowNavigation.BACK, onNavigate = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Hidden when the admin never set a name.
                if (state.serverName.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowSectionLabel(stringResource(R.string.media_servers_name_section_title))
                        FlowCard {
                            Text(
                                text = state.serverName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }

                if (headerCount > 0) {
                    FlowCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onShowHeaders)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.media_servers_add_server_custom_headers_label),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = headerCount.toString(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FlowPrimaryButton(title = primaryTitle, enabled = !state.isLoading, onClick = onStartAlternative)
                    if (state.supportsPassword) {
                        TextButton(onClick = onUsePassword, enabled = !state.isLoading) {
                            Text(
                                stringResource(R.string.media_servers_password_signin_button),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (state.isLoading) FlowLoadingOverlay()
    }
}
