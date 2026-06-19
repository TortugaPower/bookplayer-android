@file:OptIn(ExperimentalMaterial3Api::class)

package com.tortugapower.audiobookplayer.ui.screens.themes

import com.tortugapower.audiobookplayer.ui.components.SettingsToggleItem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.tortugapower.audiobookplayer.logic.ThemeManager
import com.tortugapower.audiobookplayer.repository.RoomAccountRepository
import com.tortugapower.audiobookplayer.ui.components.BookPlayerTabScaffold
import com.tortugapower.audiobookplayer.ui.theme.BookPlayerThemeSpec

import com.tortugapower.audiobookplayer.ui.screens.auth.AuthSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.BookPlayerProSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.PaywallSheet
import com.tortugapower.audiobookplayer.ui.screens.pro.ProRestoreButton
import com.tortugapower.audiobookplayer.ui.screens.pro.WelcomeToProDialog

/**
 * Theme picker with light/dark-variant + system-mode toggles and a Restore action in the top bar
 * (celebrating via [WelcomeToProDialog]).
 *
 * Themes other than Default / Pure Black are gated behind a paid (plus / lite / pro) entitlement:
 * locked rows are dimmed + non-selectable for free users, who also see a "BookPlayer Pro" upsell card whose
 * CTA runs the sign-in/paywall flow ([BookPlayerProSheet] → [AuthSheet] → [PaywallSheet]). Gaining
 * the entitlement flips `hasThemeAccess` reactively — the card hides and the themes unlock.
 *
 * @param onBack pop back to Settings
 */
@Composable
fun ThemesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Predefined themes (everything except Default / Pure Black, per Themes.json `locked`) unlock
    // for any paid tier — plus, lite, or pro (i.e. not FREE). Tier is reactive — granting an
    // entitlement (e.g. plus via a tip, or a subscription) flips this without a relaunch.
    val accountRepository = remember { RoomAccountRepository(AppDatabase.getDatabase(context).accountDao()) }
    val account by accountRepository.getAccountFlow().collectAsState(initial = null)
    val hasThemeAccess = account != null && account?.tier != AccountTier.FREE
    var showWelcome by remember { mutableStateOf(false) }

    // Pro upsell flow for free users (mirrors ProfileScreen): the card's CTA opens the Pro sheet →
    // sign-in (passkey stacks AuthSheet) → on auth, non-subscribers get the paywall. Gaining any
    // paid tier flips `hasThemeAccess`, hiding the card and unlocking the themes — reactively.
    var showProSheet by remember { mutableStateOf(false) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var showPaywall by remember { mutableStateOf(false) }

    fun onAuthenticated(hasSubscription: Boolean) {
        showAuthSheet = false
        showProSheet = false
        if (!hasSubscription) showPaywall = true
    }

    // Restoring a purchase from here celebrates but stays on the Themes screen.
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
        title = stringResource(R.string.themes_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
        },
        actions = {
            ProRestoreButton(onRestored = { showWelcome = true }) { onClick, enabled ->
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
            if (!hasThemeAccess) {
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
                    SettingsToggleItem(
                        label = stringResource(R.string.themes_use_system_mode),
                        checked = ThemeManager.useSystemMode,
                        onCheckedChange = { ThemeManager.setUseSystemMode(context, it) },
                    )
                    HorizontalDivider()
                    SettingsToggleItem(
                        label = stringResource(R.string.themes_always_use_dark),
                        checked = ThemeManager.useDarkVariant,
                        enabled = !ThemeManager.useSystemMode,
                        onCheckedChange = { ThemeManager.setUseDarkVariant(context, it) },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.themes_section_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    ThemeManager.allThemes.forEachIndexed { index, theme ->
                        ThemeItem(
                            theme = theme,
                            isSelected = ThemeManager.currentTheme.title == theme.title,
                            hasThemeAccess = hasThemeAccess,
                            onClick = { ThemeManager.setTheme(context, theme) },
                        )
                        if (index < ThemeManager.allThemes.size - 1) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun ThemeItem(theme: BookPlayerThemeSpec, isSelected: Boolean, hasThemeAccess: Boolean, onClick: () -> Unit) {
    val isLockedForUser = theme.locked && !hasThemeAccess
    ListItem(
        headlineContent = {
            Text(
                text = theme.title,
                // Locked rows read as inactive via the Material disabled-content alpha (matches the
                // dimmed SettingsToggleItem label and iOS's grayer locked text).
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isLockedForUser -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        leadingContent = {
            // The color swatch stays vibrant even when locked — only the text is dimmed.
            ThemeShowcase(
                theme = theme,
                modifier = if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else Modifier,
            )
        },
        trailingContent = if (isSelected || isLockedForUser) {
            {
                if (isSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.common_pro_locked), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !isLockedForUser, onClick = onClick),
    )
}


@Composable
private fun ThemeShowcase(theme: BookPlayerThemeSpec, modifier: Modifier = Modifier) {
    val s = theme.showcaseLightColors
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(s.background))
                Box(Modifier.weight(1f).fillMaxHeight().background(s.accent))
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(s.primary))
                Box(Modifier.weight(1f).fillMaxHeight().background(s.secondary))
            }
        }
    }
}
