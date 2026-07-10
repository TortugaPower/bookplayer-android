@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.model.formatSyncTime
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.components.LocalMiniPlayerInset
import com.tortugapower.audiobookplayer.viewmodel.ProfileViewModel
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource

import com.tortugapower.audiobookplayer.ui.screens.pro.BookPlayerProSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.PaywallSheet
import com.tortugapower.audiobookplayer.ui.screens.auth.AuthSheet

/**
 * The Profile tab. The listening overview leads the page; account entry lives in the top bar:
 * signed in → an accent-tinted avatar opening account details; signed out → a "Sign In" text
 * action opening the Pro/auth sheets. After sign-in, presents the "Complete Your Account"
 * paywall to non-subscribers (mirrors iOS `handleSignInResult`).
 *
 * @param onNavigateToAccountDetails navigate to the account-details screen (signed-in users)
 * @param onNavigateToQueuedTasks navigate to the sync queue (Pro/Lite users)
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onNavigateToAccountDetails: () -> Unit,
    onNavigateToQueuedTasks: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val context = LocalContext.current

    // Use cached account state from ViewModel
    val account by viewModel.account.collectAsStateWithLifecycle()
    val todayListenedTime by viewModel.todayListenedTime.collectAsStateWithLifecycle()
    val totalPlaytime by viewModel.totalPlaytime.collectAsStateWithLifecycle()
    val mostListenedBookArtwork by viewModel.mostListenedBookArtwork.collectAsStateWithLifecycle()
    val pendingTasksCount by viewModel.pendingTasksCount.collectAsStateWithLifecycle()
    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsStateWithLifecycle()

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

    BookPlayerTabScaffold(
        title = stringResource(R.string.profile_title),
        actions = {
            // Account entry moved out of the content (the old card clashed with the overview):
            // signed in → accent-tinted avatar opening account details; signed out → a plain
            // "Sign In" text action opening the Pro/auth sheet flow.
            if (account != null) {
                IconButton(onClick = onNavigateToAccountDetails) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = stringResource(R.string.profile_account),
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                TextButton(onClick = { showProSheet = true }) {
                    Text(
                        text = stringResource(R.string.profile_sign_in),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

        // Statistics Section
        Text(
            text = stringResource(R.string.profile_overview),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)
        )

        OverviewMainCard(
            todayPlaytime = todayListenedTime,
            totalPlaytime = totalPlaytime,
            artworkUrl = mostListenedBookArtwork
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.profile_activity),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column {
                ActivityRow(stringResource(R.string.profile_stats), Icons.Default.BarChart, onClick = onNavigateToStatistics)
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ActivityRow(stringResource(R.string.profile_listening_history), Icons.Default.History, onClick = onNavigateToHistory)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

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

        // Reserve space for the floating mini player as trailing scroll content (not an outer inset), so
        // the content still scrolls BEHIND the pill instead of stopping above it in a solid band.
        Spacer(modifier = Modifier.height(LocalMiniPlayerInset.current))
            }

            // Non-subscribers get the BookPlayer Pro callout pinned at the bottom — the same slot
            // iOS uses (ProfileProCalloutSectionView, shown whenever !hasSubscription; subscribers
            // see the sync-tasks section instead). Signed out → Pro/auth sheets; signed in without
            // a subscription → account details (mirrors iOS showLoginOrAccount).
            val isSubscribed = account?.tier == AccountTier.PRO || account?.tier == AccountTier.LITE
            if (!isSubscribed) {
                ProCallout(
                    onLearnMore = {
                        if (account == null) showProSheet = true
                        else onNavigateToAccountDetails()
                    },
                    modifier = Modifier.padding(bottom = LocalMiniPlayerInset.current)
                )
            }
        }
    }
}

/** Bottom-pinned "BookPlayer Pro" pitch for non-subscribers, mirroring iOS `ProfileProCalloutSectionView`. */
@Composable
private fun ProCallout(
    onLearnMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.pro_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onLearnMore) {
            Text(
                text = stringResource(R.string.pro_learn_more),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/**
 * Formats a total listening duration using only its largest unit ("47 min", "3h", "2 days",
 * "1 month", "1 year"), for the overview card's total-listened caption.
 */
@Composable
private fun formatLargestUnit(durationMs: Long): String {
    val minutes = durationMs / 60000
    val hours = minutes / 60
    val days = hours / 24
    val months = days / 30
    val years = days / 365
    return when {
        years > 0 -> pluralStringResource(R.plurals.profile_unit_years, years.toInt(), years.toInt())
        months > 0 -> pluralStringResource(R.plurals.profile_unit_months, months.toInt(), months.toInt())
        days > 0 -> pluralStringResource(R.plurals.profile_unit_days, days.toInt(), days.toInt())
        hours > 0 -> stringResource(R.string.profile_time_hours, hours)
        else -> stringResource(R.string.profile_time_minutes, minutes)
    }
}

@Composable
fun OverviewMainCard(
    todayPlaytime: Long,
    totalPlaytime: Long,
    artworkUrl: String?
) {
    val totalMinutes = todayPlaytime / 60000
    val displayTime = if (totalMinutes < 60) {
        stringResource(R.string.profile_time_minutes, totalMinutes)
    } else {
        stringResource(R.string.profile_time_hours_minutes, totalMinutes / 60, totalMinutes % 60)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(20.dp)),
        color = MaterialTheme.colorScheme.primary
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Darkening gradient so the white text stays readable over any artwork.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.35f),
                                1f to Color.Black.copy(alpha = 0.75f)
                            )
                        )
                )
            } else {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(130.dp),
                    alpha = 0.3f,
                    contentScale = ContentScale.Fit
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.profile_today),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = displayTime,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.profile_total_listened, formatLargestUnit(totalPlaytime)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun ActivityRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}

