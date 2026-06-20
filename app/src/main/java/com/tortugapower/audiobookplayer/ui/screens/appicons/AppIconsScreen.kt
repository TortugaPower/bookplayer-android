@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.appicons

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.tortugapower.audiobookplayer.R
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.AccountTier
import com.tortugapower.audiobookplayer.logic.AppIcon
import com.tortugapower.audiobookplayer.logic.AppIconManager
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.ui.components.AuthErrorDialog
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.screens.auth.AuthSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.BookPlayerProSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.PaywallSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.ProRestoreButton
import com.tortugapower.audiobookplayer.ui.screens.pro.WelcomeToProDialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Alternate app-icon picker. Mirrors the Themes screen: icons other than Default / Retro are gated
 * behind a paid (plus / lite / pro) entitlement — locked rows are dimmed + non-selectable for free
 * users, who also see a "BookPlayer Pro" upsell card whose CTA runs the sign-in/paywall flow
 * ([BookPlayerProSheet] → [AuthSheet] → [PaywallSheet]). Gaining the entitlement flips
 * `hasIconAccess` reactively — the card hides and the icons unlock.
 *
 * Selecting an icon enables its `<activity-alias>` (and disables the rest) via [AppIconManager];
 * the launcher icon swaps silently, though some OEM launchers only refresh after the app is closed.
 *
 * @param onBack pop back to Settings
 */
@Composable
fun AppIconsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Icons other than Default / Retro unlock for any paid tier — plus, lite, or pro (i.e. not
    // FREE). Tier is reactive — granting an entitlement (e.g. plus via a tip, or a subscription)
    // flips this without a relaunch.
    val accountRepository = remember { RoomAccountRepository(AppDatabase.getDatabase(context).accountDao()) }
    val account by accountRepository.getAccountFlow().collectAsState(initial = null)
    val hasIconAccess = account != null && account?.tier != AccountTier.FREE

    // The enabled alias component is the source of truth; seed local selection from it.
    var selectedId by remember { mutableStateOf(AppIconManager.currentIcon(context).id) }
    var showWelcome by remember { mutableStateOf(false) }

    // Toggling the alias is a synchronous PackageManager IPC that can throw on some OEMs — run it
    // off the main thread, advance the selection only on success, and surface failures (mirrors iOS).
    val scope = rememberCoroutineScope()
    var iconError by remember { mutableStateOf<String?>(null) }
    val changeFailedMessage = stringResource(R.string.app_icon_change_failed)
    AuthErrorDialog(message = iconError) { iconError = null }

    // Pro upsell flow for free users (mirrors the Themes screen): the card's CTA opens the Pro
    // sheet → sign-in (passkey stacks AuthSheet) → on auth, non-subscribers get the paywall.
    var showProSheet by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }

    fun onAuthenticated(hasSubscription: Boolean) {
        showAuthSheet = false
        showProSheet = false
        if (!hasSubscription) showPaywall = true
    }

    if (showWelcome) {
        WelcomeToProDialog(onDismiss = { showWelcome = false })
    }
    if (showProSheet) {
        BookPlayerProSheet(
            onDismiss = { showProSheet = false },
            onPasskeyClick = { showAuthSheet = true },
            onAuthenticated = ::onAuthenticated,
        )
    }
    if (showAuthSheet) {
        AuthSheet(
            onDismiss = { showAuthSheet = false },
            onAuthenticated = ::onAuthenticated,
        )
    }
    if (showPaywall) {
        PaywallSheet(onDismiss = { showPaywall = false })
    }

    BookPlayerTabScaffold(
        title = stringResource(R.string.settings_app_icon_label),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
        },
        actions = {
            ProRestoreButton(
                onRestored = { showWelcome = true },
                noneFoundMessageRes = R.string.tip_missing_title,
            ) { onClick, enabled ->
                TextButton(onClick = onClick, enabled = enabled) {
                    Text(stringResource(R.string.common_restore), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Pro upsell banner — only for free users (no plus/pro), mirroring iOS's free-only card.
            if (!hasIconAccess) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ) {
                        // Title sits on top; the icon is vertically centered against the description
                        // body only. Title + button are indented to align with the description text.
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.pro_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 56.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    modifier = Modifier.size(40.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Text(
                                    text = stringResource(R.string.pro_feature_cloud_sync_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { showProSheet = true },
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.padding(start = 56.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.pro_learn_more),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    AppIconManager.allIcons.forEachIndexed { index, icon ->
                        AppIconItem(
                            icon = icon,
                            isSelected = selectedId == icon.id,
                            hasIconAccess = hasIconAccess,
                            onClick = {
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { AppIconManager.setIcon(context, icon) }
                                    if (ok) selectedId = icon.id else iconError = changeFailedMessage
                                }
                            },
                        )
                        if (index < AppIconManager.allIcons.size - 1) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconItem(icon: AppIcon, isSelected: Boolean, hasIconAccess: Boolean, onClick: () -> Unit) {
    val isLockedForUser = !icon.free && !hasIconAccess
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(icon.titleRes),
                // Locked rows read as inactive via the Material disabled-content alpha (matches the
                // Themes screen and iOS's grayer locked text).
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isLockedForUser -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.app_icon_author),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            // The icon preview stays vibrant even when locked — only the text is dimmed.
            // Loaded via Coil (not painterResource), which rasterizes the launcher mipmaps'
            // <adaptive-icon> drawables — painterResource only supports <vector>/raster and would crash.
            AsyncImage(
                model = icon.previewRes,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        },
        trailingContent = if (isSelected || isLockedForUser) {
            {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.common_pro_locked),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !isLockedForUser, onClick = onClick),
    )
}
