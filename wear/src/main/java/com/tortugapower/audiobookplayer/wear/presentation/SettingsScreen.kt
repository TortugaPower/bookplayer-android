package com.tortugapower.audiobookplayer.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.tortugapower.audiobookplayer.database.entities.AccountEntity
import com.tortugapower.audiobookplayer.wear.R

/**
 * The Settings screen (mirrors iOS `SettingsView`): signed out → the phone sign-in handoff; signed in → a
 * Profile section (email), a Downloaded section (total storage), and destructive Delete-downloads / Sign-out
 * compact buttons (iOS `ProfileView`). Signing in as PRO flips the app to standalone.
 *
 * The short signed-out / signing-in states are vertically centered (no scroll list) so the copy sits in the
 * middle of the round face instead of colliding with the top clock; the taller profile is a scrolling list.
 */
@Composable
fun SettingsScreen(
    account: AccountEntity?,
    signInState: SignInUiState,
    storageUsed: String,
    canDelete: Boolean,
    onSignIn: () -> Unit,
    onDeleteDownloads: () -> Unit,
    onSignOut: () -> Unit,
) {
    when {
        // Loading takes precedence over the account: after a PRO sign-in the account is already set but we
        // keep the spinner until the nav host swaps to the library, so no profile flash.
        signInState == SignInUiState.Loading -> LoadingContent()
        account == null -> SignInContent(signInState, onSignIn)
        else -> ProfileContent(account, storageUsed, canDelete, onDeleteDownloads, onSignOut)
    }
}

/** In-progress handoff: just a spinner + "Signing in", centered (no title/body to overlap). */
@Composable
private fun LoadingContent() {
    CenteredScaffold {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.wear_signin_in_progress),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption1,
        )
    }
}

/** Signed-out (idle/error): centered body + "Sign in with phone". */
@Composable
private fun SignInContent(state: SignInUiState, onSignIn: () -> Unit) {
    CenteredScaffold {
        Text(
            text = stringResource(R.string.wear_sign_in_body),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.body2,
        )
        Spacer(Modifier.height(8.dp))
        Chip(
            onClick = onSignIn,
            label = { Text(stringResource(R.string.wear_signin_button)) },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state is SignInUiState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(state.error.messageRes()),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.error,
            )
        }
    }
}

/** Signed-in: Profile (email) + Downloaded (storage) sections, then destructive delete / sign-out buttons. */
@Composable
private fun ProfileContent(
    account: AccountEntity,
    storageUsed: String,
    canDelete: Boolean,
    onDeleteDownloads: () -> Unit,
    onSignOut: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScrollScaffold(listState) {
        ScalingLazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
            item { SectionHeader(R.string.wear_profile_section) }
            if (account.email.isNotBlank()) {
                item {
                    Text(
                        text = account.email,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }
            item { SectionHeader(R.string.wear_downloaded_section, topPadding = 10.dp) }
            item {
                Text(text = storageUsed, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
            }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DestructiveButton(
                        iconRes = R.drawable.ic_delete,
                        descriptionRes = R.string.wear_delete_downloads,
                        enabled = canDelete,
                        onClick = onDeleteDownloads,
                    )
                    DestructiveButton(
                        iconRes = R.drawable.ic_logout,
                        descriptionRes = R.string.wear_logout,
                        enabled = true,
                        onClick = onSignOut,
                    )
                }
            }
        }
    }
}

/** A compact destructive action: red background, white glyph (iOS's red Delete / Logout). */
@Composable
private fun DestructiveButton(iconRes: Int, descriptionRes: Int, enabled: Boolean, onClick: () -> Unit) {
    CompactButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = MaterialTheme.colors.error,
            contentColor = Color.White,
        ),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = stringResource(descriptionRes),
        )
    }
}

/** A Scaffold (curved clock preserved) whose content is vertically centered on the round face. */
@Composable
private fun CenteredScaffold(content: @Composable () -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        CenterMessage(content)
    }
}

@Composable
private fun SectionHeader(labelRes: Int, topPadding: Dp = 0.dp) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.caption1,
        color = MaterialTheme.colors.onSurfaceVariant,
        modifier = Modifier.padding(top = topPadding),
    )
}

private fun SignInError.messageRes(): Int = when (this) {
    SignInError.PHONE_NOT_REACHABLE -> R.string.wear_signin_error_not_reachable
    SignInError.PHONE_NOT_SIGNED_IN -> R.string.wear_signin_error_phone_not_signed_in
    SignInError.FAILED -> R.string.wear_signin_error_failed
}
