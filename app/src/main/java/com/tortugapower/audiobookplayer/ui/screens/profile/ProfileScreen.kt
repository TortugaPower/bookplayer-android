@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.model.formatSyncTime
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.viewmodel.ProfileViewModel

import com.tortugapower.audiobookplayer.ui.screens.pro.BookPlayerProSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.PaywallSheet
import com.tortugapower.audiobookplayer.ui.screens.auth.AuthSheet

/**
 * The Profile tab. Shows the account summary (or a "set up account" prompt when signed out),
 * listening stats, and a Pro upsell. Opens the Pro/auth sheets and, after sign-in, presents the
 * "Complete Your Account" paywall to non-subscribers (mirrors iOS `handleSignInResult`).
 *
 * @param onNavigateToAccountDetails navigate to the account-details screen (signed-in users)
 * @param onNavigateToQueuedTasks navigate to the sync queue (Pro/Lite users)
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateToAccountDetails: () -> Unit,
    onNavigateToQueuedTasks: () -> Unit
) {
    val context = LocalContext.current

    // Use cached account state from ViewModel
    val account by viewModel.account.collectAsState()
    val pendingTasksCount by viewModel.pendingTasksCount.collectAsState()
    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsState()

    var showProSheet by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }

    // After any successful sign-in/sign-up, close the auth flow and — for accounts without a
    // subscription — present the "Complete Your Account" paywall (mirrors iOS handleSignInResult).
    fun onAuthenticated(hasSubscription: Boolean) {
        showAuthSheet = false
        showProSheet = false
        if (!hasSubscription) showPaywall = true
    }

    if (showProSheet) {
        BookPlayerProSheet(
            onDismiss = { showProSheet = false },
            // Keep the Pro sheet presented underneath; the Auth sheet stacks on top so
            // dismissing it returns here rather than closing the whole flow.
            onPasskeyClick = { showAuthSheet = true },
            onAuthenticated = ::onAuthenticated
        )
    }

    if (showAuthSheet) {
        AuthSheet(
            // Cancel (✕ / back / swipe) → return to the Pro sheet underneath.
            onDismiss = { showAuthSheet = false },
            onAuthenticated = ::onAuthenticated
        )
    }

    if (showPaywall) {
        PaywallSheet(onDismiss = { showPaywall = false })
    }

    BookPlayerTabScaffold(title = stringResource(R.string.profile_title)) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            // Reserve space for the floating mini player so the bottom content isn't covered.
            .padding(bottom = LocalMiniPlayerInset.current)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Account Section
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .clickable {
                    if (account == null) showProSheet = true
                    else onNavigateToAccountDetails()
                },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (account != null) Icons.Default.Person else Icons.Default.PersonOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (account != null) account!!.email else stringResource(R.string.profile_setup_account),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (account != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = if (account?.tier == AccountTier.PRO) Color.DarkGray.copy(alpha = 0.8f)
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = account!!.tier.name.lowercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (account?.tier == AccountTier.PRO) Color.White
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.profile_not_signed_in),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Statistics Section
        Text(
            text = stringResource(R.string.profile_listening_time_value),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.profile_total_listening_time),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        // Queued Tasks Button
        if (account != null && (account!!.tier == AccountTier.PRO || account!!.tier == AccountTier.LITE)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .clickable { onNavigateToQueuedTasks() }
            ) {
                Text(
                    text = "Queued sync tasks ($pendingTasksCount)",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF3482F6),
                    fontWeight = FontWeight.Medium
                )
                if (lastSyncTimestamp != null) {
                    Text(
                        text = "Last sync: ${lastSyncTimestamp!!.formatSyncTime()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // BookPlayer Pro Section
        if (account?.tier != AccountTier.PRO) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.pro_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showProSheet = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3482F6) // Approximate blue from screenshot
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.pro_learn_more),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
    }
}

