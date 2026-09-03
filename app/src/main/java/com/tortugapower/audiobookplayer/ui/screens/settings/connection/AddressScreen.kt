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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.ExternalServiceType
import com.tortugapower.audiobookplayer.logic.ServerAddress
import com.tortugapower.audiobookplayer.viewmodel.ConnectionFlowUiState

/**
 * Screen 1 · Address. Server address as explicit fields — scheme, host (carrying any reverse-proxy
 * subpath), port — plus the custom headers, which are editable here and only here. The assembled URL
 * is shown before Connect so what will actually be dialed is visible; Connect flows after the content
 * like every action button in this flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressScreen(
    state: ConnectionFlowUiState,
    onSchemeChanged: (ServerAddress.Scheme) -> Unit,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onHeaderAdded: () -> Unit,
    onHeaderChanged: (id: Long, key: String, value: String) -> Unit,
    onHeaderRemoved: (id: Long) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    val integrationName = integrationDisplayName(state.type)
    // The integration's usual port and a hostname, shown as examples only — never substituted.
    val usualPort = ServerAddress.usualPort(state.type).toString()
    val hostPlaceholder = stringResource(
        when (state.type) {
            ExternalServiceType.JELLYFIN -> R.string.media_servers_address_host_placeholder_jellyfin
            ExternalServiceType.AUDIOBOOKSHELF -> R.string.media_servers_address_host_placeholder_audiobookshelf
        }
    )
    val schemeLabel = stringResource(R.string.media_servers_address_scheme_label)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Add Server gets a worded Cancel; the re-auth presentation gets the X the old sheet had.
            FlowHeader(
                title = integrationName,
                navigation = if (state.isReauth) FlowNavigation.CLOSE else FlowNavigation.CANCEL,
                onNavigate = onCancel,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowSectionLabel(stringResource(R.string.media_servers_server_section_title))

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = schemeLabel }
                    ) {
                        val schemes = ServerAddress.Scheme.entries
                        schemes.forEachIndexed { index, scheme ->
                            SegmentedButton(
                                selected = state.address.scheme == scheme,
                                onClick = { onSchemeChanged(scheme) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = schemes.size),
                            ) {
                                // Protocol identifiers, not words — deliberately unlocalized.
                                Text(scheme.value)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.hostText,
                        onValueChange = onHostChanged,
                        label = { Text(stringResource(R.string.media_servers_address_host_label)) },
                        placeholder = { Text(hostPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        trailingIcon = {
                            // Shown only when there is something to clear, like the system clear button.
                            if (state.hostText.isNotEmpty()) {
                                IconButton(onClick = { onHostChanged("") }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                    )

                    OutlinedTextField(
                        value = state.portText,
                        onValueChange = onPortChanged,
                        label = { Text(stringResource(R.string.media_servers_address_port_label)) },
                        placeholder = { Text(usualPort) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (state.canConnect) onConnect() }),
                        trailingIcon = {
                            if (state.portText.isNotEmpty()) {
                                IconButton(onClick = { onPortChanged("") }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_clear), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                    )

                    // The assembled URL — the part that makes a split address field trustworthy.
                    state.url?.let { url ->
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CustomHeadersEditor(
                    headers = state.headers,
                    enabled = !state.isLoading,
                    onAdd = onHeaderAdded,
                    onChange = onHeaderChanged,
                    onRemove = onHeaderRemoved,
                )

                FlowPrimaryButton(
                    title = stringResource(R.string.media_servers_add_server_connect_button),
                    enabled = state.canConnect,
                    onClick = onConnect,
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (state.isLoading) FlowLoadingOverlay()
    }
}

/** The product name of an integration — a brand, so never localized. */
fun integrationDisplayName(type: ExternalServiceType): String = when (type) {
    ExternalServiceType.JELLYFIN -> "Jellyfin"
    ExternalServiceType.AUDIOBOOKSHELF -> "AudiobookShelf"
}
