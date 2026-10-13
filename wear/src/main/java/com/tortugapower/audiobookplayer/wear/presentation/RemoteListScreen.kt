package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.tortugapower.audiobookplayer.wear.R
import com.tortugapower.audiobookplayer.wear.data.PhoneAppStatus

/**
 * Remote start destination: the recently-played list (title/author rows only — matches iOS's companion
 * `ItemListView`, no now-playing header). Tapping a row plays it on the phone and opens now-playing.
 */
@Composable
fun RemoteListScreen(
    state: RemoteUiState,
    onPlayItem: (String) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    phoneAppStatus: PhoneAppStatus?,
    phoneStoreListing: StoreListingState,
    onInstallOnPhone: () -> Unit,
) {
    when {
        // No phone state yet because the phone has no BookPlayer: waiting would never end, so offer the install.
        state.connecting && phoneAppStatus == PhoneAppStatus.APP_MISSING -> MessageScreen {
            Text(
                text = stringResource(phoneStoreListing.messageRes(R.string.wear_install_phone_body)),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
            )
            // The install chip stays after the store opened, in case the user closed it there.
            Spacer(Modifier.height(8.dp))
            InstallOnPhoneChip(phoneStoreListing, onInstallOnPhone)
            // Settings stays reachable, as on "Connecting": a signed-in watch can still sign out or free its downloads.
            Spacer(Modifier.height(4.dp))
            SettingsChip(onSettings)
        }
        state.connecting -> MessageScreen {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.wear_remote_connecting),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption1,
            )
            // Reachable even before the phone connects, so a signed-out watch isn't stranded here.
            Spacer(Modifier.height(8.dp))
            SettingsChip(onSettings)
        }
        state.recentItems.isEmpty() -> MessageScreen {
            Text(
                text = stringResource(R.string.wear_remote_empty),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2,
            )
            RefreshChip(onRefresh = onRefresh)
            Spacer(Modifier.height(4.dp))
            SettingsChip(onSettings)
        }
        else -> {
            val listState = rememberScalingLazyListState()
            ScrollScaffold(listState) {
                ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
                    item { SettingsHeaderButton(onSettings) }
                    item {
                        Text(
                            text = stringResource(R.string.wear_recent_title),
                            style = MaterialTheme.typography.caption1,
                        )
                    }
                    items(state.recentItems, key = { it.id }) { item ->
                        Chip(
                            onClick = { onPlayItem(item.id) },
                            label = { Text(item.title, maxLines = 2) },
                            secondaryLabel = { Text(item.author, maxLines = 1) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
