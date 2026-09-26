package com.tortugapower.audiobookplayer.ui.screens.settings.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.viewmodel.HeaderEntry

/**
 * Read-only view of the headers the pending connection carries. A pushed screen rather than a dialog:
 * dialogs don't scroll, truncate long values, and can't be copied — and these are frequently secrets,
 * which is the whole reason custom headers exist. Values render in full; nothing in a header map says
 * which value is a credential, so any masking rule would be guesswork.
 */
@Composable
fun HeadersDetailScreen(
    headers: List<HeaderEntry>,
    onBack: () -> Unit,
) {
    val entries = headers.filter { it.key.isNotBlank() && it.value.isNotBlank() }

    Column(modifier = Modifier.fillMaxSize()) {
        FlowHeader(
            title = stringResource(R.string.media_servers_add_server_custom_headers_label),
            navigation = FlowNavigation.BACK,
            onNavigate = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowCard {
                entries.forEachIndexed { index, entry ->
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = entry.key.trim(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(text = entry.value.trim(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (index < entries.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), thickness = 0.5.dp)
                    }
                }
            }
            Text(
                text = stringResource(R.string.media_servers_headers_detail_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
