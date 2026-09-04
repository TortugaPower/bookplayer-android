package com.tortugapower.audiobookplayer.ui.screens.settings.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.viewmodel.HeaderEntry

/**
 * The editable custom-header rows (name + value per row, a delete button, an "Add Header" row),
 * shown on the address screen only. Headers are the one thing that can be edited about a saved
 * connection, and they are edited here — the connection-details screen shows them read-only.
 */
@Composable
fun CustomHeadersEditor(
    headers: List<HeaderEntry>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onChange: (id: Long, key: String, value: String) -> Unit,
    onRemove: (id: Long) -> Unit,
    /** Rows that won't be sent (see `ConnectionFlowViewModel.droppedHeaderIds`); their key is struck through. */
    dropped: Set<Long> = emptySet(),
) {
    // The strikethrough waits until the row loses focus, so the user doesn't see "crossed-out" text mid-typing.
    var focusedId by remember { mutableStateOf<Long?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowSectionLabel(stringResource(R.string.media_servers_add_server_custom_headers_label))

        FlowCard {
            headers.forEachIndexed { index, entry ->
                key(entry.id) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val struck = entry.id in dropped && focusedId != entry.id
                            TextField(
                                value = entry.key,
                                onValueChange = { onChange(entry.id, it, entry.value) },
                                placeholder = { Text(stringResource(R.string.media_servers_add_server_header_name_placeholder)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { if (it.isFocused) focusedId = entry.id else if (focusedId == entry.id) focusedId = null },
                                colors = transparentFieldColors(),
                                singleLine = true,
                                enabled = enabled,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                            )
                            TextField(
                                value = entry.value,
                                onValueChange = { onChange(entry.id, entry.key, it) },
                                placeholder = { Text(stringResource(R.string.media_servers_add_server_header_value_placeholder)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { if (it.isFocused) focusedId = entry.id else if (focusedId == entry.id) focusedId = null },
                                colors = transparentFieldColors(),
                                singleLine = true,
                                enabled = enabled,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                                textStyle = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onRemove(entry.id) }, enabled = enabled) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.common_remove),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    if (index < headers.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onAdd)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.AddCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(R.string.media_servers_add_server_add_header_button),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Text(
            text = stringResource(R.string.media_servers_add_server_headers_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
